package org.wso2.carbon.apimgt.gateway;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.commons.throttle.core.CallerConfiguration;
import org.apache.synapse.commons.throttle.core.CallerContext;
import org.apache.synapse.commons.throttle.core.SharedParamManager;
import org.apache.synapse.commons.throttle.core.ThrottleContext;
import org.apache.synapse.commons.throttle.core.internal.DistributedThrottleProcessor;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import redis.clients.jedis.JedisPool;

import java.util.Calendar;
import java.util.HashMap;

public class ClockTimeBasedHybridThrottleProcessor implements DistributedThrottleProcessor {
    JedisPool redisPool;
    private String gatewayId;
    HashMap<String, String> syncModeNotifiedMap = new HashMap<>();
    private static Log log = LogFactory.getLog(HybridThrottleProcessor.class.getName());


    public ClockTimeBasedHybridThrottleProcessor() {
        redisPool = ServiceReferenceHolder.getInstance().getRedisPool();
        RedisConfig redisConfig = org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder.
                getInstance().getAPIManagerConfigurationService().getAPIManagerConfiguration().getRedisConfig();
        gatewayId = redisConfig.getGatewayId();
    }

    @Override
    public boolean canAccessBasedOnUnitTime(CallerContext callerContext, CallerConfiguration configuration, ThrottleContext throttleContext, long currentTime) {
        setLocalQuota(callerContext, configuration);
        setThrottleParamSyncMode(callerContext, currentTime);
        int maxRequest = configuration.getMaximumRequestPerUnitTime();

        boolean canAccess = false;

        // 1- requests at async mode
        if (callerContext.getLocalHits() < callerContext.getLocalQuota()) {
            callerContext.incrementLocalHits();
            canAccess = true;
        } else if (callerContext.getLocalHits() == callerContext.getLocalQuota() && !callerContext.isThrottleParamSyncingModeSync() /** TODO: canAccess == true*/) {
            callerContext.incrementLocalHits();

            //set the sync mode
            callerContext.setIsThrottleParamSyncingModeSync(true);
            canAccess = true;

            // 2- requests at sync mode
        } else if (callerContext.getGlobalCounter() < maxRequest) { // haven't synced with redis yet. so check with locally stored global counter
            //determine the shared counter identifier for the current time unit window
            long timeWindowStartTimestamp = getTimeWindowStartTimestamp(currentTime, configuration.getUnitTime());

            // format of the distributed counter key: "sharedCounter-timeWindowStartTimestamp" e.g. sharedCounter-1512086400000
            long distributedCounter = SharedParamManager.addAndGetDistributedCounter(String.valueOf(timeWindowStartTimestamp), 1);

            if (distributedCounter <= maxRequest) {
                canAccess = true;
            }
        }

        return canAccess;
    }

    private long getTimeWindowStartTimestamp(long currentTime, long unitTime) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(currentTime);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }


    @Override
    public boolean canAccessIfUnitTimeNotOver(CallerContext callerContext, CallerConfiguration callerConfiguration, ThrottleContext throttleContext, long l) {
        return false;
    }

    @Override
    public boolean canAccessIfUnitTimeOver(CallerContext callerContext, CallerConfiguration callerConfiguration, ThrottleContext throttleContext, long l) {
        return false;
    }

    @Override
    public void syncThrottleCounterParams(CallerContext callerContext, boolean b, long l) {

    }

    @Override
    public void syncThrottleWindowParams(CallerContext callerContext, boolean b) {

    }

    @Override
    public String getType() {
        return null;
    }

    @Override
    public boolean isEnable() {
        return false;
    }

    /**
     * //TODO:move to a supper class
     * Calculate and set the local quota to the caller context. This is done for each request since the gateway count
     * can be changed dynamically.
     *
     * @param callerContext
     * @param configuration
     * @return
     */
    public void setLocalQuota(CallerContext callerContext, CallerConfiguration configuration) {
        long maxRequests = configuration.getMaximumRequestPerUnitTime();
        int gatewayCount = ServiceReferenceHolder.getInstance().getGatewayCount();

        //if min GW count is defined

        long localQuota = (maxRequests - maxRequests * 20 / 100) / gatewayCount;
        log.trace("### Set local quota to " + localQuota + " for " + callerContext.getId() + " in hybrid throttling" + " Thread name: " + Thread.currentThread().getName()
                + " Thread id: " + Thread.currentThread().getId());
        callerContext.setLocalQuota(localQuota);
    }

    private void setThrottleParamSyncMode(CallerContext callerContext, long currentTime) { // TODO: refactor method params
        //iterate over the map syncModeNotifiedSet
        log.trace("Setting ThrottleParam Sync Mode for callerContext" + callerContext.getId() + ". \nsyncModeNotifiedMap:" + syncModeNotifiedMap.entrySet() + " Thread name: " + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
        if (!callerContext.isThrottleParamSyncingModeSync()) { // if async TODO: switch if else order
            if (syncModeNotifiedMap.containsKey(callerContext.getId())) { // if a sync mode switching msg has been received or own node exceeded local quota
                callerContext.setIsThrottleParamSyncingModeSync(true);
                log.trace("/////////////////  ### Set ThrottleParamSyncingModeSync to true for callerContext: " + callerContext.getId() + " Thread name: " + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
            }
        }
    }
}

package org.wso2.carbon.apimgt.gateway;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.commons.throttle.core.*;
import org.apache.synapse.commons.throttle.core.internal.DistributedThrottleProcessor;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HybridThrottleProcessor implements DistributedThrottleProcessor {
    private static Log log = LogFactory.getLog(HybridThrottleProcessor.class.getName());
    private static final String WSO2_SYNC_MODE_INIT_CHANNEL = "wso2_sync_mode_init_channel";
    private static final String THROTTLE_PARAM_PROCESS_MODE_SYNC = "sync";
    /**
     * callerContextId to nextTimeWindow mapping
     */
    HashMap<String, String> syncModeNotifiedMap = new HashMap<>();
    JedisPool redisPool;
    private Map<Long, CallerContext> callersMap;
    private ThrottleDataHolder dataHolder;
    private String gatewayId;

    public HybridThrottleProcessor() {
        redisPool = ServiceReferenceHolder.getInstance().getRedisPool();
        RedisConfig redisConfig = org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder.
                getInstance().getAPIManagerConfigurationService().getAPIManagerConfiguration().getRedisConfig();
        gatewayId = redisConfig.getGatewayId();

        Thread thread = new Thread() {
            public void run() {
                log.info("Channel subscribing Thread Running");
                JedisPubSub jedisPubSub = new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        super.onSubscribe(channel, subscribedChannels);
                        log.info("Client is Subscribed to " + channel);
                        log.info("Client is Subscribed to " + subscribedChannels + " no. of channels");
                    }

                    @Override
                    public void onMessage(String channel, String syncModeInitMsg) {
                        super.onMessage(channel, syncModeInitMsg);
                        log.info("Sync mode changed message received. Channel: " + channel + " Msg: " + syncModeInitMsg);
                        if (syncModeInitMsg.startsWith(gatewayId)) {
                            log.info("Ignoring ! as message received to own node. ");
                            return;
                        }
                        log.info("******************* Message received. CC Channel: " + channel + " Msg: " + syncModeInitMsg);
                        String[] messageParts = syncModeInitMsg.split("___");
                        String callerContextId = messageParts[1]; // [0] = gatewayId , [1] = callerContextId , [2] = nextTimeWindow
                        String nextTimeWindow = messageParts[2];
                        log.info("Going to put callerContextId: " + callerContextId + " into syncModeNotifiedSet with nextTimeWindow: " + nextTimeWindow + "(" + getReadableTime(Long.parseLong(nextTimeWindow)) + " )");
                        syncModeNotifiedMap.put(callerContextId, nextTimeWindow);
                        log.info("\n*************** Caller " + syncModeInitMsg + " SWITCHED TO SYNC MODE by message received ! :");
                        // sync throttle params to redis to consider local unpublished request counts in distributed counters
                        // RoleBaseCallerContext roleBaseCallerContext = new RoleBaseCallerContext(message);

                        if (dataHolder != null) {
                            log.info("******************* dataHolder is not null so running syncing tasks");
                            CallerContext callerContext = dataHolder.getCallerContext(callerContextId);
                            if (callerContext != null) {
                                log.info("******************* running syncing tasks for callerContext: " + callerContext.getId());
                                syncThrottleWindowParams(callerContext);
                                syncThrottleCounterParams(callerContext, false);
                            } else {
                                log.info("******************* callerContext is null so not running syncing tasks");
                            }
                        } else {
                            log.info("******************* dataHolder is null so not running syncing tasks");
                        }
                    }
                };
                try (Jedis jedis = redisPool.getResource()) {
                    jedis.subscribe(jedisPubSub, WSO2_SYNC_MODE_INIT_CHANNEL); /* TODO: will have to do above again and again in case redis server shut down at some time, the channel will be gone from redis */
                }
            }
        };
        thread.start();

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        String gatewayCountCheckingFrequency = "30000";
        //TODO: make the second arg of scheduleAtFixedRate() configurable
        executor.scheduleAtFixedRate(new ChannelSubscriptionCounterTask(), 15000,
                Integer.parseInt(gatewayCountCheckingFrequency), TimeUnit.MILLISECONDS);

    }

    private class ChannelSubscriptionCounterTask implements Runnable {

        @Override
        public void run() {
            log.info("ChannelSubscriptionCounterTask Thread Running");
            Map<String, String> channelCountMap;
            try (Jedis jedis = redisPool.getResource()) {
                channelCountMap = jedis.pubsubNumSub(WSO2_SYNC_MODE_INIT_CHANNEL);
            }
            // iterate over the map entries
            for (Map.Entry<String, String> entry : channelCountMap.entrySet()) {
                // access each entry
                String channel = entry.getKey();
                int gatewayCount = Integer.parseInt(entry.getValue());
                log.info(">>> channel: " + channel + " subscription count: " + gatewayCount);
                ServiceReferenceHolder.getInstance().setGatewayCount(gatewayCount);
                log.info("### Set GW count to: " + gatewayCount);
            }
            log.info("D \n");

        }
    }

    @Override
    public boolean canAccessBasedOnUnitTime(CallerContext callerContext, CallerConfiguration configuration, ThrottleContext throttleContext, long currentTime) {
        log.info("### canAccessBasedOnUnitTime Thread name: " + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
        setLocalQuota(callerContext, configuration); // TODO: remove (and add to a proper place) if not needed to do this always.
        setThrottleParamSyncMode(callerContext);

        if (dataHolder == null) {
            dataHolder = (ThrottleDataHolder) throttleContext.getConfigurationContext().getPropertyNonReplicable(ThrottleConstants.THROTTLE_INFO_KEY);
        }

        boolean canAccess;

        // added  the or condition [callerContext.getNextTimeWindow() == 0] to handle the case where the nextTimeWindow is not set yet. i.e.
        // when the first request comes in for a given callerContext to this GW node.
        if (callerContext.getNextTimeWindow() > currentTime /*|| callerContext.getNextTimeWindow() == 0*/) {
            canAccess = canAccessIfUnitTimeNotOver(callerContext, configuration, throttleContext, currentTime);
        } else {
            canAccess = canAccessIfUnitTimeOver(callerContext, configuration, throttleContext, currentTime);
        }
        if (canAccess) {
            callerContext.incrementLocalHits();
            log.info("&&&  CCcA localHits:" + callerContext.getLocalHits());
        }


        if (callerContext.getLocalHits() == callerContext.getLocalQuota()) {
            log.info("\n\n ///////////////// quota reached. SWITCHED TO SYNC MODE !!!. callerContext.getLocalHits()  : " + callerContext.getLocalHits() + "\n");
            callerContext.setIsThrottleParamSyncingModeSync(true);
            callerContext.setSyncModeLastUpdatedTime(System.currentTimeMillis());
            String message = gatewayId + "___" + callerContext.getId() + "___" + callerContext.getNextTimeWindow();

            if (dataHolder != null) {
                log.info("******************* dataHolder is not null so running syncing tasks");
                syncThrottleWindowParams(callerContext);
                syncThrottleCounterParams(callerContext, false);
            }
            syncModeNotifiedMap.put(callerContext.getId(), String.valueOf(callerContext.getNextTimeWindow()));
            try (Jedis jedis = redisPool.getResource()) {
                //String message = callerContext.getId();
                jedis.publish(WSO2_SYNC_MODE_INIT_CHANNEL, message);
            }
        }
        return canAccess;
    }

    private void setThrottleParamSyncMode(CallerContext callerContext) { // TODO: refactor method params
        //iterate over the map syncModeNotifiedSet
        log.info("Setting ThrottleParam Sync Mode for callerContext" + callerContext.getId() +  ". \nsyncModeNotifiedMap:" + syncModeNotifiedMap.entrySet());
        if (!callerContext.isThrottleParamSyncingModeSync()) { // TODO: switch if else order
            if (syncModeNotifiedMap.containsKey(callerContext.getId())) {
                long nextTimeWindowOfSyncMessage = Long.parseLong(syncModeNotifiedMap.get(callerContext.getId()));
                long currentTime = System.currentTimeMillis();
                if (nextTimeWindowOfSyncMessage >= currentTime) { // still within the time window that the sync message was sent by some GW node
                    callerContext.setIsThrottleParamSyncingModeSync(true);
                    callerContext.setSyncModeLastUpdatedTime(currentTime);
                }
                log.info("/////////////////  ### Set ThrottleParamSyncingModeSync to true for callerContext: " + callerContext.getId());
            }
        } else {
            log.info("/////////////////  ### ThrottleParamSyncingModeSync is already true for callerContext: " + callerContext.getId());
            // check if the sync mode was set 'sync' in a previous time window, that should not be taken into consideration
            if (callerContext.getSyncModeLastUpdatedTime() > callerContext.getNextTimeWindow()) { // TODO: debug & check if callerContext.getNextTimeWindow() is the one set in previous time window
                log.info("/////////////////  ### But the last updated time is in a previous time window. So setting it to false. So setting it to false.");
                callerContext.setIsThrottleParamSyncingModeSync(false);
            }
        }
    }

    @Override
    public boolean canAccessIfUnitTimeNotOver(CallerContext callerContext, CallerConfiguration configuration, ThrottleContext throttleContext, long currentTime) {
        log.info("### Running canAccessIfUnitTime NotOver ");
        boolean canAccess = false;
        int maxRequest = configuration.getMaximumRequestPerUnitTime();
        boolean localCounterReseted = true;
        // log.info("canAccessIfUnitTimeNotOver** : currentTime now:" + currentTime); // >>>
        if (maxRequest != 0) {
            if (callerContext.isThrottleParamSyncingModeSync() /*&& callerContext.getLocalHits() >= callerContext.getLocalQuota()*/) {
                log.info("&&&  Going to run throttle param syncing in sync mode"); // local count is incremented in here
                syncThrottleWindowParams(callerContext);
                syncThrottleCounterParams(callerContext, true); // add piled items and new request item to shared-counter (increments before allowing the request)
            } else {
                callerContext.incrementLocalCounter();
                localCounterReseted = false;
            }

            log.info("CallerContext Checking access if unit time is not over and less than max count>> Access "
                    + "allowed=" + maxRequest + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter())
                    + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime="
                    + currentTime + " " + "nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow()) + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier="
                    + configuration.getID() + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime())));

            if (callerContext.getFirstAccessTime() > callerContext.getNextAccessTime()) { // to resolve the test 4 issue
                callerContext.setNextAccessTime(0);
            }

            if (callerContext.getGlobalCounter() <= maxRequest) {    //If the globalCount is less than max request // very first requests to setup hits into this block
                log.info("&&& If the globalCount is less than max request : (callerContext.getglobalCount.get() + callerContext.getlocalCount.get()) = " + (callerContext.getGlobalCounter() + callerContext.getLocalCounter())); // >>>
                if (log.isDebugEnabled()) {
                    log.debug("CallerContext Checking access if unit time is not over and less than max count>> Access "
                            + "allowed=" + maxRequest + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter())
                            + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime="
                            + currentTime + " " + "nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow()) + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier="
                            + configuration.getID() + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime())));
                }
                canAccess = true;     // can continue access
                // callerContext.incrementLocalCounter(); // incremented in syncThrottleWindowParams()
                log.info("$$$ CC_UTNO1 localCount:" + callerContext.getLocalCounter());

                throttleContext.flushCallerContext(callerContext, callerContext.getId()); // TODO: remove if not needed to do this
                // can complete access
            } else { // if  count has exceeded max request count : set the nextAccessTime

                // if first exceeding request  (nextAccessTime = 0)
                if (callerContext.getNextAccessTime() == 0) {
                    // log.info("&&&  8 canAccessIfUnitTimeNotOver** if caller has not already prohibit (nextAccessTime == 0)");
                    //and if there is no prohibit time  period in configuration
                    long prohibitTime = configuration.getProhibitTimePeriod();
                    log.info("C-canAccessIfUnitTimeNotOver** : prohibitTime:" + prohibitTime);
                    if (prohibitTime == 0) {
                        //prohibit access until unit time period is over
                        callerContext.setNextAccessTime(callerContext.getFirstAccessTime() + configuration.getUnitTime());
                    } else {
                        //if there is a prohibit time period in configuration ,then
                        //set it as prohibit period
                        callerContext.setNextAccessTime(currentTime + prohibitTime);
                    }
                    if (log.isDebugEnabled()) {
                        String type = ThrottleConstants.IP_BASE == configuration.getType() ?
                                "IP address" : "domain";
                        log.debug("Maximum Number of requests are reached for caller with "
                                + type + " - " + callerContext.getId());
                    }
                    // Send the current state to others (clustered env)
                    throttleContext.flushCallerContext(callerContext, callerContext.getId()); // TODO: remove this if not needed

                    // No need to process/sync throttle params in sync mode from now onwards, as the requests will not be allowed anyhow
                    callerContext.setIsThrottleParamSyncingModeSync(false);
                    syncModeNotifiedMap.remove(callerContext.getId());
                    log.info("===> mode set back to async since request count has exceeded max limit");
                } else { // second to onwards exceeding requests //  TODO: CC1: analyze this and change if needed
                    // log.info("canAccessIfUnitTimeNotOver** Else of (if caller has not already prohibit) : (nextAccessTime != 0)");
                    if (callerContext.getNextAccessTime() <= currentTime) { // if the caller has already prohibit and prohibit
                        // time period has already over
                        if (log.isDebugEnabled()) {
                            log.debug("CallerContext Checking access if unit time is not over before time window exceed >> "
                                    + "Access allowed=" + maxRequest + " available="
                                    + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter()))
                                    + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter()
                                    + " currentTime=" + currentTime + " " + "nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow())
                                    + " currentLocalCount=" + callerContext.getLocalCounter() + " " + "Tier=" + configuration.getID()
                                    + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime()));
                        }
                        // remove previous caller context
                        if (callerContext.getNextTimeWindow() != 0) {
                            throttleContext.removeCallerContext(callerContext.getId());
                        }
                        // reset the states so that, this is the first access
                        callerContext.setNextAccessTime(0);
                        canAccess = true;

                        callerContext.setIsThrottleParamSyncingModeSync(false); // as this is the first access
                        syncModeNotifiedMap.remove(callerContext.getId());

                        callerContext.setGlobalCounter(0);// can access the system   and this is same as first access
                        callerContext.setLocalCounter(1); // TODO : CHECK THIS and set to 0 if needed
                        callerContext.setFirstAccessTime(currentTime);
                        callerContext.setNextTimeWindow(currentTime + configuration.getUnitTime());
                        log.info("$$$UTNO globalCount:" + callerContext.getGlobalCounter() + " , localCount:" + callerContext.getLocalCounter() +
                                ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime()) + " , nextTimeWindow:" + getReadableTime(callerContext.getNextTimeWindow()));
                        throttleContext.replicateTimeWindow(callerContext.getId()); // 1-WindowReplicator   TODO: remove if not needed
                        throttleContext.addAndFlushCallerContext(callerContext, callerContext.getId()); // 2-ThrottleCounterReplicator  TODO: remove if not needed

                        if (log.isDebugEnabled()) {
                            log.debug("Caller=" + callerContext.getId() + " has reset counters and added for replication when unit "
                                    + "time is not over");
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            String type = ThrottleConstants.IP_BASE == configuration.getType() ?
                                    "IP address" : "domain";
                            log.debug("Prohibit period is not yet over for caller with "
                                    + type + " - " + callerContext.getId());
                        }
                    }
                }
            }
            if (!localCounterReseted && canAccess == false) { // if the request was not allowed, then need to reset the local counter
                callerContext.resetLocalCounter(); //
            }
        }
        log.info("$$$ Decision made. Can access: " + canAccess);
        return canAccess;
    }

    @Override
    public boolean canAccessIfUnitTimeOver(CallerContext callerContext, CallerConfiguration configuration,
                                           ThrottleContext throttleContext, long currentTime) {
        log.info("### Running canAccessIfUnitTime Over ");
        boolean canAccess = false;
        // if number of access for a unit time is less than MAX and
        // if the unit time period (session time) has just over
        int maxRequest = configuration.getMaximumRequestPerUnitTime();
        log.info("%%% : canAccessIfUnitTimeOver**  globalCount:" + callerContext.getGlobalCounter() + " , localCount:" + callerContext.getLocalCounter() +
                ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime()) + " , nextTimeWindow:" + getReadableTime(callerContext.getNextTimeWindow()) + " localHits:"
                + callerContext.getLocalHits() + " isThrottleParamSyncingModeSync:" + callerContext.isThrottleParamSyncingModeSync() + " , nextAccessTime:" + getReadableTime(callerContext.getNextAccessTime()));
        boolean isThrottleParamSyncingModeSync_local = false;

        if (callerContext.isThrottleParamSyncingModeSync()) { // TODO: may be possible to shift this block of code to a method
            log.info("&&&  Going to run throttle param syncing");
            syncThrottleWindowParams(callerContext);
            syncThrottleCounterParams(callerContext, true); // add piled items and new request item to shared-counter (increments before allowing the request)
        }
        if (maxRequest != 0) {
            // first req, after exceeding previous window if, in previous window the max limit was not exceeded
            if ((callerContext.getGlobalCounter() + callerContext.getLocalCounter()) < maxRequest) {
                log.info("%%%AAA");


                if (callerContext.getNextTimeWindow() != 0) {
                    log.info("%%%BBB");
                    // Removes and sends the current state to others  (clustered env)
                    //remove previous callercontext instance
                    throttleContext.removeCallerContext(callerContext.getId());
                    callerContext.setGlobalCounter(0);// can access the system   and this is same as first access
                    callerContext.setLocalCounter(1);
                    callerContext.setLocalHits(0);
                    callerContext.setFirstAccessTime(currentTime);
                    callerContext.setNextTimeWindow(currentTime + configuration.getUnitTime());
//                    if (!ThrottleServiceDataHolder.getInstance().getThrottleProperties().isThrottleSyncAsyncHybridModeEnabled()) {
//                        throttleContext.replicateTimeWindow(callerContext.getId());
//                    }
                    // registers caller and send the current state to others (clustered env)
                    throttleContext.addAndFlushCallerContext(callerContext, callerContext.getId());
                    log.info("%%% : canAccessIfUnitTimeOver**  globalCount:" + callerContext.getGlobalCounter() + " , localCount:" + callerContext.getLocalCounter() +
                            ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime()) + " , nextTimeWindow:" + getReadableTime(callerContext.getNextTimeWindow()));

                }
                if (log.isDebugEnabled()) {
                    log.debug("CallerContext Checking access if unit time over next time window>> Access allowed="
                            + maxRequest + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter()))
                            + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime=" + currentTime
                            + " nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow()) + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier="
                            + configuration.getID() + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime()));
                }
                if (isThrottleParamSyncingModeSync_local) { //TODO : remove this condition considering localHit
                    log.info("%%% Going to run throttle param syncing in sync mode");
                    syncThrottleWindowParams(callerContext);
                    syncThrottleCounterParams(callerContext, true);
                }
                if (callerContext.getGlobalCounter() <= maxRequest) {
                    canAccess = true;
                }
                //next time callers can access as a new one
            } else { // if in previous window, the max limit was exceeded
                log.info("CCC");

                // if caller in prohibit session  and prohibit period has just over
                if ((callerContext.getNextAccessTime() == 0) || (callerContext.getNextAccessTime() <= currentTime)) {


                    log.debug("CallerContext Checking access if unit time over>> Access allowed=" + maxRequest
                            + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter())) + " key=" + callerContext.getId()
                            + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime=" + currentTime + " nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow())
                            + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier=" + configuration.getID() + " nextAccessTime="
                            + getReadableTime(callerContext.getNextAccessTime()));

                    //remove previous callercontext instance
                    if (callerContext.getNextTimeWindow() != 0) {
                        throttleContext.removeCallerContext(callerContext.getId());
                    }
                    // reset the states so that, this is the first access
                    callerContext.setNextAccessTime(0);
                    canAccess = true;
                    log.info("### 9 - canAccessIfUnitTimeOver***");

                    //setIsThrottleParamSyncingModeSync(false); // as canAccess is set as 'true'
                    callerContext.setLocalHits(0);

                    callerContext.setGlobalCounter(0);// can access the system   and this is same as first access
                    callerContext.setLocalCounter(1);
                    callerContext.setFirstAccessTime(currentTime);

                    // convert currentTime to readable forma

                    callerContext.setNextTimeWindow(currentTime + configuration.getUnitTime());
                    // registers caller and send the current state to others (clustered env)
                    throttleContext.addAndFlushCallerContext(callerContext, callerContext.getId());
                    log.info("DDD : canAccessIfUnitTimeOver**  globalCount:" + callerContext.getGlobalCounter() + " , localCount:" + callerContext.getLocalCounter() +
                            ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime()) + " , nextTimeWindow:" + getReadableTime(callerContext.getNextTimeWindow()));
                    if (log.isDebugEnabled()) {
                        log.debug("Caller=" + callerContext.getId() + " has reset counters and added for replication when unit "
                                + "time is over");
                    }
                    if (isThrottleParamSyncingModeSync_local) { // TODO: remove this condition considering localHits
                        log.info("%%% Going to run throttle param syncing in sync mode");
                        syncThrottleWindowParams(callerContext);
                        syncThrottleCounterParams(callerContext, true);
                    }
                    if (callerContext.getGlobalCounter() <= maxRequest) {
                        canAccess = true;
                    }

                } else {
                    // if  caller in prohibit session  and prohibit period has not  over
                    if (log.isDebugEnabled()) {
                        String type = ThrottleConstants.IP_BASE == configuration.getType() ?
                                "IP address" : "domain";
                        log.debug("Even unit time has over , CallerContext in prohibit state :"
                                + type + " - " + callerContext.getId());
                    }
                }
            }

        }
        return canAccess;
    }

    /**
     * Syncs the throttle window parameters
     *  @param callerContext
     *  @param isInvocationFlow else is that the flow is just a syncing flow which doesn't increase counters
     *
     *
     */
    @Override
    public void syncThrottleCounterParams(CallerContext callerContext, boolean isInvocationFlow) {
        log.info("\n\n///////////////// &&& Running throttleCounterParamSync(). Thread name:" + Thread.currentThread().getName());
        synchronized (callerContext.getId().intern()) {
            log.info("CallerContext.getNextTimeWindow() :" + callerContext.getNextTimeWindow() + "(" + getReadableTime(callerContext.getNextTimeWindow()) + ")" + " Thread name:" + Thread.currentThread().getName());
            if (callerContext.getNextTimeWindow() > System.currentTimeMillis()) {
                log.info("Running counter sync task");
                String id = callerContext.getId();
                log.info("### Initial Local counter:" + callerContext.getLocalCounter() + " , globalCounter:" + callerContext.getGlobalCounter() + " distributedCounter :" + SharedParamManager.getDistributedCounter(id) + " Thread name:" + Thread.currentThread().getName());
                if (isInvocationFlow) {
                    callerContext.incrementLocalCounter(); // increment local counter to consider current request
                }
                long localCounter = callerContext.getLocalCounter();
                log.info("///////////////// $$$ 4.1 localCounter increased to:" + localCounter + " Thread name:" + Thread.currentThread().getName());

                callerContext.resetLocalCounter();
                //Long distributedCounter = SharedParamManager.asyncGetAndAddDistributedCounter(id, localCounter);
                //if (isInvocationFlow) { // removing this if condition broke the flow at: Test 4: step 3: throttled at request no:4
                     SharedParamManager.addAndGetDistributedCounter(id, localCounter);
                //}

                log.info("After calling addAndGetDistributedCounter, checking the shared counter availability now");
                Long distributedCounter = SharedParamManager.getDistributedCounter(id); // getCounter()
                log.info("///////////////// finally distributedCounter :" + distributedCounter + " Thread name:" + Thread.currentThread().getName());


                //Update instance's global counter value with distributed counter
                long x = callerContext.getGlobalCounter();
                //callerContext.setGlobalCounter(distributedCounter + localCounter);
                callerContext.setGlobalCounter(distributedCounter);
                log.info("///////////////// &&&  4.2 finally globalCounter increased from:" + x + " to : " + callerContext.getGlobalCounter() + " Thread name:" + Thread.currentThread().getName());
                log.info("///////////////// &&&  finally local counter reseted to 0\n");
            } else {
                log.info("Counter Sync task skipped");
            }
        }
    }

    @Override
    public void syncThrottleWindowParams(CallerContext callerContext) {
        synchronized (callerContext.getId().intern()) {
            log.info("\n\n /////////////////  5 - Running throttleWindowParamSync. Thread name: " + Thread.currentThread().getName());

            // ThrottleWindowReplicator run() method
            String callerId = callerContext.getId();
            long sharedTimestamp = SharedParamManager.getSharedTimestamp(callerContext.getId());  // this will be set 0 if the redis key-value pair is not available
            //1
            log.info("TTL of sharedTimetamp:" + SharedParamManager.getTtl("startedTime-" + callerContext.getId()));
            //2
            long sharedNextWindow = sharedTimestamp + callerContext.getUnitTime();
            long localFirstAccessTime = callerContext.getFirstAccessTime();
            // First if statement check whether local first access time is lower than the current
            // global counter if so it will adjust the local first access time to global time to
            // adjust the time window
            log.info("/////////////////   INITIAL ** sharedTimestamp :" + getReadableTime(sharedTimestamp) + " sharedNextWindow :" + getReadableTime(sharedNextWindow) + " localFirstAccessTime :" + getReadableTime(localFirstAccessTime) + "  callerContext.getUnitTime():" + callerContext.getUnitTime() + " Thread name:" + Thread.currentThread().getName());

            log.info("///////////////// localCounter:" + callerContext.getLocalCounter() + ", globalCounter:" + callerContext.getGlobalCounter() + ", localHits:" + callerContext.getLocalHits());
            if (localFirstAccessTime < sharedTimestamp) {  // TODO:  this condition needs review
                log.info("///////////////// Hit if ***** A1");
                long distributedCounter = SharedParamManager.getDistributedCounter(callerId);
                log.info("distributedCounter :" + distributedCounter);
                callerContext.setFirstAccessTime(sharedTimestamp);
                callerContext.setNextTimeWindow(sharedNextWindow);
                callerContext.setGlobalCounter(distributedCounter);
                if (log.isDebugEnabled()) {
                    log.debug("///////////////// Setting time windows of caller context when window already set=" + callerId);
                }
                //If some request comes to a nodes after some node set the shared timestamp then this
                // check whether the first access time of local is in between the global time window
                // if so this will set local caller context time window to global
            } else if (localFirstAccessTime == sharedTimestamp) { // if this node itself set the shared timestamp
                callerContext.setGlobalCounter(SharedParamManager.getDistributedCounter(callerId));
                log.info("/////////////////&&&  localFirstAccessTime == sharedTimestamp");
                log.info("///////////////// &&&  - globalCounter :" + callerContext.getGlobalCounter());
            } else if (localFirstAccessTime > sharedTimestamp    // if another node had set the shared timestamp, earlier
                    && localFirstAccessTime < sharedNextWindow) {
                log.info("///////////////// Hit ELSE-IF**** A2");

                callerContext.setFirstAccessTime(sharedTimestamp);
                callerContext.setNextTimeWindow(sharedNextWindow);
                log.info("///////////////// &&&  - distributedCounter :" + SharedParamManager.getDistributedCounter(callerId));
                callerContext.setGlobalCounter(SharedParamManager.getDistributedCounter(callerId));
                if (log.isDebugEnabled()) {
                    log.debug("///////////////// Setting time windows of caller context in intermediate interval=" +
                            callerId);
                }
                log.info("///////////////// &&&  - getGlobalCounter :" + callerContext.getGlobalCounter());
                //If above two statements not meets, this is the place where node set new window if
                // global first access time is 0, then it will be the beginning of the throttle time time
                // window so present node will set shared timestamp and the distributed counter. Also if time
                // window expired this will be the node who set the next time window starting time
            } else {
                log.info("\n\n ///////////////// Hit Else**** A3");  // In the flow this is the first time that reaches throttleWindowParamSync method. And then at canAccessIfUnitTimeOver flow, the first call after the sharedTimestamp is removed from redis. // seems this block is not setting shared values correctly in redis
                log.info("\n\nCalling setSharedTimestamp");
                SharedParamManager.setSharedTimestamp(callerId, localFirstAccessTime);
                //3

                log.info("\n\n Calling setDistributedCounter");
                log.info("checking getCounter before setting it. getCounter Value::");
                log.info("value:" + SharedParamManager.getDistributedCounter(callerId));
                //4

                // log.info( "Do getDistributedCounter to check whether the counter exists in redis. Result:" + SharedParamManager.getDistributedCounter(callerId));
                SharedParamManager.setDistributedCounter(callerId, 0);
                //5

                //log.info("\n\n Before calling setExpiryTime method");
                //      long sharedTimestamp2 = SharedParamManager.getSharedTimestamp(callerContext.getId());
                //      long sharedTimestamp3 = SharedParamManager.getDistributedCounter(callerContext.getId());

                log.info("\n\n Calling setExpiryTime");
                SharedParamManager.setExpiryTime(callerId,
                        callerContext.getUnitTime() + localFirstAccessTime);
                //6
                //Reset global counter here as throttle replicator task may have updated global counter
                //with dirty value
                //resetGlobalCounter();
                //callerContext.setLocalCounter(1)
                //log.info("///////////////// &&&  - after setting GlobalCounter :" + callerContext.getGlobalCounter());
                //setLocalCounter(1);//Local counter will be set to one as new time window starts
                // if (log.isDebugEnabled()) {
                log.info("\n ///////////////// Completed resetting time window of=" + callerId);
                // }
            }
            log.info("///////////////// STWP: Method final: ** sharedTimestamp :" + getReadableTime(SharedParamManager.getSharedTimestamp(callerId)) +
                    " sharedNextWindow :" + getReadableTime(sharedNextWindow) + " localFirstAccessTime :" + getReadableTime(localFirstAccessTime));
        }
    }

    /**
     * Calculate and set the local quota to the caller context
     *
     * @param callerContext
     * @param configuration
     * @return
     */
    public void setLocalQuota(CallerContext callerContext, CallerConfiguration configuration) {
        long maxRequests = configuration.getMaximumRequestPerUnitTime();
        int gatewayCount = ServiceReferenceHolder.getInstance().getGatewayCount();
        long localQuota = (maxRequests - maxRequests * 20 / 100) / gatewayCount;
        log.info("### Set local quota to " + localQuota + " for " + callerContext.getId() + " in hybrid throttling");
        callerContext.setLocalQuota(localQuota);
    }

    @Override
    public String getType() {
        return "hybrid";
    }

    @Override
    public boolean isEnable() {
        return true;
    }

    public String getReadableTime(long time) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
        Date date = new Date(time);
        String formattedTime = dateFormat.format(date);
        return formattedTime;
    }
}

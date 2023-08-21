/*
 * Copyright (c) 2023, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.commons.throttle.core.*;
import org.apache.synapse.commons.throttle.core.internal.DistributedThrottleProcessor;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This class is responsible for processing throttle conditions in order to throttle based on subscription burst
 * controlling and backend throttling requests. The throttle condition evaluation is done considering a time window
 * which is based on the start timestamp of the initial request.
 */
public class HybridThrottleProcessor implements DistributedThrottleProcessor {
    private static Log log = LogFactory.getLog(HybridThrottleProcessor.class.getName());
    private static final String WSO2_SYNC_MODE_INIT_CHANNEL = "wso2_sync_mode_init_channel";
    /**
     * callerContextId to nextTimeWindow mapping
     */
    HashMap<String, String> syncModeNotifiedMap = new HashMap<>();
    JedisPool redisPool;
    private ThrottleDataHolder dataHolder;
    private String gatewayId;

    public HybridThrottleProcessor() {
        redisPool = ServiceReferenceHolder.getInstance().getRedisPool();
        RedisConfig redisConfig = org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder.
                getInstance().getAPIManagerConfigurationService().getAPIManagerConfiguration().getRedisConfig();
        gatewayId = redisConfig.getGatewayId();

        Thread thread = new Thread() {
            public void run() {
                if (log.isTraceEnabled()) {
                    log.trace("Channel subscribing Thread Running");
                }
                JedisPubSub jedisPubSub = new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        super.onSubscribe(channel, subscribedChannels);
                        if (log.isTraceEnabled()) {
                            log.trace("Gateway is Subscribed to " + channel);
                        }
                    }

                    @Override
                    public void onUnsubscribe(String channel, int subscribedChannels) {
                        super.onUnsubscribe(channel, subscribedChannels);
                        log.warn("Gateway client is Unsubscribed from channel: " + channel);
                    }

                    @Override
                    public void onMessage(String channel, String syncModeInitMsg) {
                        super.onMessage(channel, syncModeInitMsg);
                        if (log.isTraceEnabled()) {
                            log.trace("\n\nSync mode changed message received. Channel: " + channel + " Msg: " + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                        }
                        if (syncModeInitMsg.startsWith(gatewayId)) {
                            if (log.isTraceEnabled()) {
                                log.trace("Ignoring as message received to own node ! " + GatewayUtils.getThreadNameAndIdToLog());
                            }
                            return;
                        }
                        if (log.isTraceEnabled()) {
                            log.trace("Message received from channel: " + channel + " Message: " + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                        }
                        String[] messageParts = syncModeInitMsg.split("___");
                        // messageParts[0] = gatewayId , messageParts[1] = callerContextId , messageParts[2] = nextTimeWindow
                        String callerContextId = messageParts[1];
                        String nextTimeWindow = messageParts[2];
                        if (log.isTraceEnabled()) {
                            log.trace("Going to put callerContextId: " + callerContextId + " into syncModeNotifiedSet with nextTimeWindow: "
                                    + nextTimeWindow + "(" + getReadableTime(Long.parseLong(nextTimeWindow)) + " )"
                                    + GatewayUtils.getThreadNameAndIdToLog());
                        }

                        syncModeNotifiedMap.put(callerContextId, nextTimeWindow);
                        if (log.isTraceEnabled()) {
                            log.trace("\n Caller " + syncModeInitMsg + " SWITCHED TO SYNC MODE by message received ! :" + GatewayUtils.getThreadNameAndIdToLog());
                        }
                        // sync throttle params to redis to consider local unpublished request counts in distributed counters
                        // RoleBaseCallerContext roleBaseCallerContext = new RoleBaseCallerContext(message);


                        if (dataHolder != null) {
                            if (log.isTraceEnabled()) {
                                log.trace("******************* dataHolder is not null so running syncing tasks" + " message:" + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                            }

                            CallerContext callerContext = dataHolder.getCallerContext(callerContextId);
                            if (callerContext != null) {
                                if (log.isTraceEnabled()) {
                                    log.trace("******************* running forced syncing tasks for callerContext: " + callerContext.getId()
                                            + " message:" + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                                }

                                if (SharedParamManager.lockSharedKeys(callerContext.getId(), gatewayId)) {
                                    long syncingStartTime = System.currentTimeMillis();
                                    syncThrottleWindowParams(callerContext, false);
                                    syncThrottleCounterParams(callerContext, false, new RequestContext(System.currentTimeMillis()));
                                    SharedParamManager.releaseSharedKeys(callerContext.getId());
                                    long timeNow = System.currentTimeMillis();
                                    log.debug("current time:" + timeNow + "(" + getReadableTime(timeNow) + ")" + "In force syncing process, Lock released in " + (timeNow - syncingStartTime) + " ms for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                                } else {
                                    if (log.isTraceEnabled()) {
                                        log.trace("current time:" + System.currentTimeMillis() + "(" + getReadableTime(System.currentTimeMillis()) + ")" + "******************* failed to acquire lock for callerContext: " + callerContext.getId()
                                                + " message:" + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                                    }

                                }
                            } else {
                                if (log.isTraceEnabled()) {
                                    log.trace("******************* callerContext is null so not running syncing tasks" + " message:" + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                                }
                            }
                        } else {
                            if (log.isTraceEnabled()) {
                                log.trace("******************* dataHolder is null so not running syncing tasks" + " message:" + syncModeInitMsg + GatewayUtils.getThreadNameAndIdToLog());
                            }
                        }
                    }
                };
                subscribeWithRetry(jedisPubSub);
            }

            public void subscribeWithRetry(JedisPubSub jedisPubSub) {
                try (Jedis jedis = redisPool.getResource()) {
                    jedis.subscribe(jedisPubSub, WSO2_SYNC_MODE_INIT_CHANNEL);
                } catch (JedisConnectionException e) {
                    log.error("Could not establish connection by retrieving a resource from the redis pool. So error occurred while subscribing to channel: " + WSO2_SYNC_MODE_INIT_CHANNEL, e);
                    log.info("Next retry to subscribe to channel " + WSO2_SYNC_MODE_INIT_CHANNEL + " + in 10 seconds");
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                    subscribeWithRetry(jedisPubSub);
                }
            }
        };
        thread.start();

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        String gatewayCountCheckingFrequency = "30000";
        //TODO: Decide whether to make the first and second arg of scheduleAtFixedRate() configurable
        executor.scheduleAtFixedRate(new ChannelSubscriptionCounterTask(), 15000,
                Integer.parseInt(gatewayCountCheckingFrequency), TimeUnit.MILLISECONDS);

    }

    private class ChannelSubscriptionCounterTask implements Runnable {

        @Override
        public void run() {
            //log.trace("ChannelSubscriptionCounterTask Thread Running");
            Map<String, String> channelCountMap;
            try (Jedis jedis = redisPool.getResource()) {
                channelCountMap = jedis.pubsubNumSub(WSO2_SYNC_MODE_INIT_CHANNEL);
            }
            // iterate over the map entries
            for (Map.Entry<String, String> entry : channelCountMap.entrySet()) {
                // access each entry
                String channel = entry.getKey();
                int gatewayCount = Integer.parseInt(entry.getValue());
                //log.trace(">>> channel: " + channel + " subscription count: " + gatewayCount);
                ServiceReferenceHolder.getInstance().setGatewayCount(gatewayCount);
                if (log.isTraceEnabled()) {
                    log.trace("### ChannelSubscriptionCounterTask : channel: " + channel + " Set GW count to: " + gatewayCount);
                }
            }
        }
    }

    @Override
    public boolean canAccessBasedOnUnitTime(CallerContext callerContext, CallerConfiguration configuration, ThrottleContext throttleContext, RequestContext requestContext) {
        if (log.isTraceEnabled()) {
            log.trace("### canAccessBasedOnUnitTime Thread name: " + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
        }
        setLocalQuota(callerContext, configuration);
        setThrottleParamSyncMode(callerContext, requestContext);

        if (dataHolder == null) {
            dataHolder = (ThrottleDataHolder) throttleContext.getConfigurationContext().getPropertyNonReplicable(ThrottleConstants.THROTTLE_INFO_KEY);
        }

        boolean canAccess;

        // added  the or condition [callerContext.getNextTimeWindow() == 0] to handle the case where the nextTimeWindow is not set yet. i.e.
        // when the first request comes in for a given callerContext to this GW node.
        if (callerContext.getNextTimeWindow() > requestContext.getRequestTime() /*|| callerContext.getNextTimeWindow() == 0*/) {
            canAccess = canAccessIfUnitTimeNotOver(callerContext, configuration, throttleContext, requestContext);
        } else {
            canAccess = canAccessIfUnitTimeOver(callerContext, configuration, throttleContext, requestContext);
        }
        if (canAccess) {
            callerContext.incrementLocalHits();
            if (log.isTraceEnabled()) {
                log.trace("&&&  CCcA localHits:" + callerContext.getLocalHits() + GatewayUtils.getThreadNameAndIdToLog());
            }
        }

        // convert the sync mode to sync and publish mode-changing message to redis only if the syncing mode is still async and requests are not yet throttled.
        // "canAccess == true" condition is checked to avoid unnecessary syncings and redis publish messages when the requests are already throttled. (after the
        // requests are throttled, next requests are processed in async mode)
        if (callerContext.getLocalHits() == callerContext.getLocalQuota() && !callerContext.isThrottleParamSyncingModeSync() && canAccess == true) {
            if (log.isTraceEnabled()) {
                log.trace("\n\n ///////////////// quota reached. SWITCHED TO SYNC MODE !!!. callerContext.getLocalHits()  : "
                        + callerContext.getLocalHits() + "\n" + GatewayUtils.getThreadNameAndIdToLog());
            }

            callerContext.setIsThrottleParamSyncingModeSync(true);
            String message = gatewayId + "___" + callerContext.getId() + "___" + callerContext.getNextTimeWindow();

            if (dataHolder != null) {
                if (log.isTraceEnabled()) {
                    log.trace("******************* dataHolder is not null so running syncing tasks" + GatewayUtils.getThreadNameAndIdToLog());

                }
                if (SharedParamManager.lockSharedKeys(callerContext.getId(), gatewayId)) {
                    long syncingStartTime = System.currentTimeMillis();
                    syncThrottleWindowParams(callerContext, true);
                    syncThrottleCounterParams(callerContext, false, requestContext);
                    SharedParamManager.releaseSharedKeys(callerContext.getId());
                    long timeNow = System.currentTimeMillis();
                    log.debug("current time:" + timeNow + "(" + getReadableTime(timeNow) + ")" + "In canAccessBasedOnUnitTime, Lock released in " + (timeNow - syncingStartTime) + " ms for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                } else {
                    log.warn("current time:" + System.currentTimeMillis() + "(" + getReadableTime(System.currentTimeMillis()) + ")" + "canAccessBasedOnUnitTime Syncing Throttle params, skipped. Failed to lock shared keys, hence skipped syncing tasks. key: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                }
            }
            log.debug("Sync mode started: request time:" + requestContext.getRequestTime() + "(" + getReadableTime(requestContext.getRequestTime()) + ")"
                    + " firstAccessTime: (" + callerContext.getFirstAccessTime() + ")" + getReadableTime(callerContext.getFirstAccessTime()) +
                    " callerContext.getNextTimeWindow():" + callerContext.getNextTimeWindow() + "(" + getReadableTime(callerContext.getNextTimeWindow())
                    + ")" + " nextAccessTime:" + callerContext.getNextAccessTime() + "(" + getReadableTime(callerContext.getNextAccessTime()) + ")" + "  localHits: " + callerContext.getLocalHits() + " localQuota: " + callerContext.getLocalQuota() +
                    GatewayUtils.getThreadNameAndIdToLog());
            syncModeNotifiedMap.put(callerContext.getId(), String.valueOf(callerContext.getNextTimeWindow()));
            try (Jedis jedis = redisPool.getResource()) {
                if (log.isTraceEnabled()) {
                    log.trace("Publishing message to channel. message: " + message + GatewayUtils.getThreadNameAndIdToLog());
                }
                jedis.publish(WSO2_SYNC_MODE_INIT_CHANNEL, message);
            }
        }
        return canAccess;
    }

    private void setThrottleParamSyncMode(CallerContext callerContext, RequestContext requestContext) {
        //iterate over the map syncModeNotifiedSet
        if (log.isTraceEnabled()) {
            log.trace("Setting ThrottleParam Sync Mode for callerContext" + callerContext.getId() + ". \nsyncModeNotifiedMap:" + syncModeNotifiedMap.entrySet() + GatewayUtils.getThreadNameAndIdToLog());
        }
        if (callerContext.isThrottleParamSyncingModeSync()) { // if async
            if (log.isTraceEnabled()) {
                log.trace("/////////////////  ### ThrottleParamSyncingModeSync is already true for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
            }
            if (requestContext.getRequestTime() > callerContext.getNextTimeWindow()) { // previous time window is exceeded and this is the first request in new window
                // normally SyncModeLastUpdatedTime is less than NextTimeWindow. If so we need to check if this nextTimeWindow is an old one too. (previous window is passed now)
                if (log.isTraceEnabled()) {
                    log.trace("/////////////////  ### currentTime has exceeded NextTimeWindow. So setting it to false. So setting it to false." + GatewayUtils.getThreadNameAndIdToLog());
                }
                callerContext.setIsThrottleParamSyncingModeSync(false);
            }
        } else {
            if (syncModeNotifiedMap.containsKey(callerContext.getId())) { // if a sync mode switching msg has been received or own node exceeded local quota
                long nextTimeWindowOfSyncMessage = Long.parseLong(syncModeNotifiedMap.get(callerContext.getId()));
                if (nextTimeWindowOfSyncMessage >= requestContext.getRequestTime()) { // still within the time window that the sync message was sent by some other GW node or mode switched by own node
                    callerContext.setIsThrottleParamSyncingModeSync(true);
                    if (log.isTraceEnabled()) {
                        log.trace("/////////////////  ### Set ThrottleParamSyncingModeSync to true for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                    }
                }
                //log.trace("/////////////////  ### Set ThrottleParamSyncingModeSync to true for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
            }
        }
    }

    @Override
    public boolean canAccessIfUnitTimeNotOver(CallerContext callerContext, CallerConfiguration configuration, ThrottleContext throttleContext, RequestContext requestContext) {
        if (log.isTraceEnabled()) {
            log.trace("### Running canAccessIfUnitTime NotOver " + GatewayUtils.getThreadNameAndIdToLog());
        }
        boolean canAccess = false;
        int maxRequest = configuration.getMaximumRequestPerUnitTime();
        boolean localCounterReseted = true;
        // log.trace("canAccessIfUnitTimeNotOver** : currentTime now:" + currentTime); // >>>
        if (maxRequest != 0) {
            if (callerContext.isThrottleParamSyncingModeSync() /*&& callerContext.getLocalHits() >= callerContext.getLocalQuota()*/) {
                if (log.isTraceEnabled()) {
                    log.trace("&&&  Going to run throttle param syncing in sync mode" + GatewayUtils.getThreadNameAndIdToLog()); // local count is incremented in here
                }
                if (SharedParamManager.lockSharedKeys(callerContext.getId(), gatewayId)) {
                    long syncingStartTime = System.currentTimeMillis();
                    syncThrottleWindowParams(callerContext, true);
                    syncThrottleCounterParams(callerContext, true, requestContext); // add piled items and new request item to shared-counter (increments before allowing the request)
                    SharedParamManager.releaseSharedKeys(callerContext.getId());
                    long timeNow = System.currentTimeMillis();
                    log.debug("current time:" + timeNow + "(" + getReadableTime(timeNow) + ")" + "In canAccessIfUnitTimeNotOver Lock released in " + (System.currentTimeMillis() - syncingStartTime) + " ms for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                } else {
                    log.warn("current time:" + System.currentTimeMillis() + "(" + getReadableTime(System.currentTimeMillis()) + ")" + " In canAccessIfUnitTimeNotOver : Failed to lock shared keys, hence skipped syncing tasks. key=" + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                    callerContext.incrementLocalCounter(); // increment local counter since, sync tasks didn't run where incrementing should have happened (https://github.com/wso2/api-manager/issues/1982#issuecomment-1624920455)
                }
            } else { //async mode
                if (log.isTraceEnabled()) {
                    log.trace("&&& In canAccessIfUnitTimeNotOver Serving api calls in async mode" + GatewayUtils.getThreadNameAndIdToLog());
                }
                callerContext.incrementLocalCounter();
                localCounterReseted = false;
            }

            if (log.isTraceEnabled()) {
                log.trace("CallerContext Checking access if unit time is not over and less than max count>> Access "
                        + "allowed=" + maxRequest + " available=" + (maxRequest - (callerContext.getGlobalCounter()
                        + callerContext.getLocalCounter() - 1) + " key=" + callerContext.getId() + " currentGlobalCount="
                        + callerContext.getGlobalCounter() + " currentTime=" + requestContext.getRequestTime() + "(" + getReadableTime(requestContext.getRequestTime()) + ") "
                        + "nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow()) + " currentLocalCount="
                        + callerContext.getLocalCounter() + " Tier=" + configuration.getID() + " nextAccessTime="
                        + getReadableTime(callerContext.getNextAccessTime()) + " firstAccessTime:" + callerContext.getFirstAccessTime()
                        + "(" + getReadableTime(callerContext.getFirstAccessTime()) + ")") + " Thread name: "
                        + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
            }

            if (callerContext.getFirstAccessTime() >= callerContext.getNextAccessTime()) { // to resolve the test 4 issue
                callerContext.setNextAccessTime(0);
                if (log.isTraceEnabled()) {
                    log.trace("A- nextAccessTime is setted to 0" + GatewayUtils.getThreadNameAndIdToLog());
                }
            }

            // >>>>>>>>>>
            if (callerContext.getNextAccessTime() < callerContext.getFirstAccessTime()) {

            }

            // >>>>>>>>>>>

            if (callerContext.getGlobalCounter() <= maxRequest) {    //(If the globalCount is less than max request). // Very first requests to cluster hits into this block
                if (log.isTraceEnabled()) {
                    log.trace("&&& If the globalCount is less than max request : (callerContext.getglobalCount.get() + callerContext.getlocalCount.get()) = " + (callerContext.getGlobalCounter() + callerContext.getLocalCounter()) + GatewayUtils.getThreadNameAndIdToLog()); // >>>
                    log.trace("In canAccessIfUnitTimeNotOver Values:  "
                            + "allowed=" + maxRequest + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter())
                            + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime="
                            + requestContext.getRequestTime() + "(" + getReadableTime(requestContext.getRequestTime()) + ") " + "nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow()) + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier="
                            + configuration.getID() + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime())
                            + " firstAccessTime:" + callerContext.getFirstAccessTime() + "(" + getReadableTime(callerContext.getFirstAccessTime()) + ")")
                            + GatewayUtils.getThreadNameAndIdToLog());
                }
                canAccess = true;     // can continue access
                // callerContext.incrementLocalCounter(); // incremented in syncThrottleWindowParams()
                if (log.isTraceEnabled()) {
                    log.trace("$$$ CC_UTNO1 localCount:" + callerContext.getLocalCounter() + GatewayUtils.getThreadNameAndIdToLog());
                }

                throttleContext.flushCallerContext(callerContext, callerContext.getId()); // TODO: remove if not needed to do this
                // can complete access
            } else { // if  count has exceeded max request count : set the nextAccessTime

                // if first exceeding request  (nextAccessTime = 0)
                if (callerContext.getNextAccessTime() == 0) { // @@@@ 1
                    // log.trace("&&&  8 canAccessIfUnitTimeNotOver** if caller has not already prohibit (nextAccessTime == 0)");
                    //and if there is no prohibit time  period in configuration
                    long prohibitTime = configuration.getProhibitTimePeriod();
                    if (log.isTraceEnabled()) {
                        log.trace("C-canAccessIfUnitTimeNotOver** : prohibitTime:" + prohibitTime + GatewayUtils.getThreadNameAndIdToLog());
                    }
                    if (prohibitTime == 0) {
                        //prohibit access until unit time period is over
                        callerContext.setNextAccessTime(callerContext.getFirstAccessTime() + configuration.getUnitTime());
                    } else {
                        //if there is a prohibit time period in configuration ,then
                        //set it as prohibit period
                        callerContext.setNextAccessTime(requestContext.getRequestTime() + prohibitTime);
                    }
                    if (log.isTraceEnabled()) {
                        String type = ThrottleConstants.IP_BASE == configuration.getType() ?
                                "IP address" : "domain";
                        log.trace("Maximum Number of requests are reached for caller with "
                                + type + " - " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                    }
                    // Send the current state to others (clustered env)
                    throttleContext.flushCallerContext(callerContext, callerContext.getId()); // TODO: remove this if not needed

                    // No need to process/sync throttle params in sync mode from now onwards, as the requests will not be allowed anyhow
                    callerContext.setIsThrottleParamSyncingModeSync(false);
                    syncModeNotifiedMap.remove(callerContext.getId());
                    if (log.isTraceEnabled()) {
                        log.trace("===> mode set back to async since request count has exceeded max limit" + GatewayUtils.getThreadNameAndIdToLog());
                    }
                } else { // second to onwards exceeding requests : conditions based on prohibit time period comes into
                    // action here onwards since 1st exceeding request had set the prohibit period if there is any
                    if (callerContext.getNextAccessTime() <= requestContext.getRequestTime()) { // if the caller has already prohibit and prohibit
                        // time period has already over
                        if (log.isTraceEnabled()) {
                            log.trace("CallerContext Checking access if unit time is not over before time window exceed >> "
                                    + "Access allowed=" + maxRequest + " available="
                                    + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter()))
                                    + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter()
                                    + " currentTime=" + requestContext.getRequestTime() + " " + "nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow())
                                    + " currentLocalCount=" + callerContext.getLocalCounter() + " " + "Tier=" + configuration.getID()
                                    + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime())
                                    + GatewayUtils.getThreadNameAndIdToLog());
                        }
                        // remove previous caller context
                        if (callerContext.getNextTimeWindow() != 0) {
                            throttleContext.removeCallerContext(callerContext.getId());
                        }
                        // reset the states so that, this is the first access
                        if (log.isTraceEnabled()) {
                            log.trace("B- nextAccessTime is setted to 0" + GatewayUtils.getThreadNameAndIdToLog());
                        }

                        callerContext.setNextAccessTime(0);
                        canAccess = true;

                        callerContext.setIsThrottleParamSyncingModeSync(false); // as this is the first access
                        syncModeNotifiedMap.remove(callerContext.getId());

                        // trouble occurs from below line >>>>>>>>>>>>>
                        callerContext.setGlobalCounter(0);// can access the system   and this is same as first access
                        callerContext.setLocalCounter(1);
                        callerContext.setLocalHits(0);
                        callerContext.setFirstAccessTime(requestContext.getRequestTime());
                        callerContext.setNextTimeWindow(requestContext.getRequestTime() + configuration.getUnitTime());
                        if (log.isTraceEnabled()) {
                            log.trace("$$$UTNO globalCount:" + callerContext.getGlobalCounter() + " , localCount:" + callerContext.getLocalCounter() +
                                    ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime())
                                    + " , nextTimeWindow:" + getReadableTime(callerContext.getNextTimeWindow())
                                    + GatewayUtils.getThreadNameAndIdToLog());
                        }

                        // throttleContext.replicateTimeWindow(callerContext.getId()); // 1-WindowReplicator   TODO: remove if not needed
                        throttleContext.addAndFlushCallerContext(callerContext, callerContext.getId()); //

                        if (log.isTraceEnabled()) {
                            log.trace("Caller=" + callerContext.getId() + " has reset counters and added for replication when unit "
                                    + "time is not over" + GatewayUtils.getThreadNameAndIdToLog());
                        }
                    } else {
                        if (log.isTraceEnabled()) {
                            String type = ThrottleConstants.IP_BASE == configuration.getType() ?
                                    "IP address" : "domain";
                            log.trace("There is no prohibit period or the prohibit period is not yet over for caller with "
                                    + type + " - " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                        }
                    }
                }
            }
            if (!localCounterReseted && canAccess == false) { // if throttle param processing was async and if the request was not allowed, then need to reset the local counter and hits
                callerContext.resetLocalCounter(); //
                callerContext.setLocalHits(0);
                if (log.isTraceEnabled()) {
                    log.trace("Check if this log is hit. If not, can remove this condition; and will need to move the setLocalHits() call to outside. NOTE NOTE NOTE NOTE NOTE NOTE NOTE NOTE NOTE NOTE NOTE NOTE\n  NOTE NOTE NOTE "
                            + GatewayUtils.getThreadNameAndIdToLog());
                }
            }
        }
        log.debug(" request time:" + requestContext.getRequestTime() + "(" + getReadableTime(requestContext.getRequestTime()) + ")" + "$$$ In canAccessIfUnitTimeNotOver:  DECISION MADE. CAN ACCESS: " + canAccess
                + " firstAccessTime: (" + callerContext.getFirstAccessTime() + ")" + getReadableTime(callerContext.getFirstAccessTime()) +
                " callerContext.getNextTimeWindow():" + callerContext.getNextTimeWindow() + "(" + getReadableTime(callerContext.getNextTimeWindow())
                + ")" + " nextAccessTime:" + callerContext.getNextAccessTime() + "(" + getReadableTime(callerContext.getNextAccessTime()) + ")" + " localHits:" +
                callerContext.getLocalHits() + " globalHits :" + callerContext.getGlobalCounter() + " Thread name: " +
                Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
        return canAccess;
    }

    @Override
    public boolean canAccessIfUnitTimeOver(CallerContext callerContext, CallerConfiguration configuration,
                                           ThrottleContext throttleContext, RequestContext requestContext) {
        if (log.isTraceEnabled()) {
            log.trace("### Running canAccessIfUnitTime Over " + GatewayUtils.getThreadNameAndIdToLog());
        }
        boolean canAccess = false;
        // if number of access for a unit time is less than MAX and
        // if the unit time period (session time) has just over
        int maxRequest = configuration.getMaximumRequestPerUnitTime();
        if (log.isTraceEnabled()) {
            log.trace("%%% : canAccessIfUnitTimeOver**  globalCount:" + callerContext.getGlobalCounter() + " , localCount:"
                    + callerContext.getLocalCounter() + ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime())
                    + " , nextTimeWindow:" + getReadableTime(callerContext.getNextTimeWindow()) + " localHits:" + callerContext.getLocalHits()
                    + " isThrottleParamSyncingModeSync:" + callerContext.isThrottleParamSyncingModeSync() + " , nextAccessTime:"
                    + getReadableTime(callerContext.getNextAccessTime()) + GatewayUtils.getThreadNameAndIdToLog());
        }


        if (callerContext.isThrottleParamSyncingModeSync()) {
            if (log.isTraceEnabled()) {
                log.trace("&&&  Going to run throttle param syncing" + GatewayUtils.getThreadNameAndIdToLog());
            }
            if (SharedParamManager.lockSharedKeys(callerContext.getId(), gatewayId)) {
                long syncingStartTime = System.currentTimeMillis();
                syncThrottleWindowParams(callerContext, true);
                syncThrottleCounterParams(callerContext, true, requestContext); // add piled items and new request item to shared-counter (increments before allowing the request)
                SharedParamManager.releaseSharedKeys(callerContext.getId());
                long timeNow = System.currentTimeMillis();

                log.debug("current time:" + timeNow + "(" + getReadableTime(timeNow) + ")" + "In canAccessIfUnitTimeOver Lock released in " + (timeNow - syncingStartTime) + " ms for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
            } else {
                log.warn("current time:" + System.currentTimeMillis() + "(" + getReadableTime(System.currentTimeMillis()) + ")" + " In canAccessIfUnitTimeOver : Failed to lock shared keys, hence skipped syncing tasks. key=" + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                callerContext.incrementLocalCounter(); // increment local counter since, sync tasks didn't run where incrementing should have happened (https://github.com/wso2/api-manager/issues/1982#issuecomment-1624920455)
            }
        } else {
            if (log.isTraceEnabled()) {
                log.trace("&&& In canAccessIfUnitTimeOver  Serving api calls in async mode" + GatewayUtils.getThreadNameAndIdToLog());
            }
        }
        if (maxRequest != 0) {
            // first req, after exceeding previous window if, in previous window the max limit was not exceeded
            if ((callerContext.getGlobalCounter() + callerContext.getLocalCounter()) < maxRequest) {
                if (log.isTraceEnabled()) {
                    log.trace("%%%AAA" + GatewayUtils.getThreadNameAndIdToLog());
                }


                if (callerContext.getNextTimeWindow() != 0) {
                    if (log.isTraceEnabled()) {
                        log.trace("%%%BBB" + GatewayUtils.getThreadNameAndIdToLog());
                    }
                    // Removes and sends the current state to others  (clustered env)
                    //remove previous callercontext instance
                    throttleContext.removeCallerContext(callerContext.getId());
                    callerContext.setGlobalCounter(0);// can access the system   and this is same as first access
                    callerContext.setLocalCounter(1);
                    callerContext.setLocalHits(0);
                    callerContext.setFirstAccessTime(requestContext.getRequestTime());
                    callerContext.setNextTimeWindow(requestContext.getRequestTime() + configuration.getUnitTime());
//                    if (!ThrottleServiceDataHolder.getInstance().getThrottleProperties().isThrottleSyncAsyncHybridModeEnabled()) {
//                        throttleContext.replicateTimeWindow(callerContext.getId());
//                    }
                    // registers caller and send the current state to others (clustered env)
                    throttleContext.addAndFlushCallerContext(callerContext, callerContext.getId());
                    if (log.isTraceEnabled()) {
                        log.trace("%%% : canAccessIfUnitTimeOver**  globalCount:" + callerContext.getGlobalCounter()
                                + " , localCount:" + callerContext.getLocalCounter() + ", firstAccessTime:"
                                + getReadableTime(callerContext.getFirstAccessTime()) + " , nextTimeWindow:"
                                + getReadableTime(callerContext.getNextTimeWindow()) + GatewayUtils.getThreadNameAndIdToLog());
                    }


                }
                if (log.isTraceEnabled()) {
                    log.trace("CallerContext Checking access if unit time over next time window>> Access allowed="
                            + maxRequest + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter()))
                            + " key=" + callerContext.getId() + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime=" + requestContext.getRequestTime()
                            + " nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow()) + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier="
                            + configuration.getID() + " nextAccessTime=" + getReadableTime(callerContext.getNextAccessTime())
                            + GatewayUtils.getThreadNameAndIdToLog());
                }
                if (callerContext.getGlobalCounter() <= maxRequest) {
                    canAccess = true;
                }
                //next time callers can access as a new one
            } else { // if in previous window, the max limit was exceeded
                // if caller in prohibit session  and prohibit period has just over
                if ((callerContext.getNextAccessTime() == 0) || (callerContext.getNextAccessTime() <= requestContext.getRequestTime())) { // @@@ 3
                    if (log.isTraceEnabled()) {
                        log.trace("CallerContext Checking access if unit time over>> Access allowed=" + maxRequest
                                + " available=" + (maxRequest - (callerContext.getGlobalCounter() + callerContext.getLocalCounter())) + " key=" + callerContext.getId()
                                + " currentGlobalCount=" + callerContext.getGlobalCounter() + " currentTime=" + requestContext.getRequestTime() + " nextTimeWindow=" + getReadableTime(callerContext.getNextTimeWindow())
                                + " currentLocalCount=" + callerContext.getLocalCounter() + " Tier=" + configuration.getID() + " nextAccessTime="
                                + getReadableTime(callerContext.getNextAccessTime()) + GatewayUtils.getThreadNameAndIdToLog());
                    }

                    //remove previous callercontext instance
                    if (callerContext.getNextTimeWindow() != 0) {
                        throttleContext.removeCallerContext(callerContext.getId());
                    }
                    // reset the states so that, this is the first access
                    callerContext.setNextAccessTime(0);
                    canAccess = true;

                    //setIsThrottleParamSyncingModeSync(false); // as canAccess is set as 'true'
                    callerContext.setLocalHits(0);

                    callerContext.setGlobalCounter(0);// can access the system   and this is same as first access
                    callerContext.setLocalCounter(1);
                    callerContext.setFirstAccessTime(requestContext.getRequestTime());

                    // convert currentTime to readable forma

                    callerContext.setNextTimeWindow(requestContext.getRequestTime() + configuration.getUnitTime());
                    // registers caller and send the current state to others (clustered env)
                    throttleContext.addAndFlushCallerContext(callerContext, callerContext.getId());
                    if (log.isTraceEnabled()) {
                        log.trace("DDD : canAccessIfUnitTimeOver**  globalCount:" + callerContext.getGlobalCounter() + " , localCount:" + callerContext.getLocalCounter() +
                                ", firstAccessTime:" + getReadableTime(callerContext.getFirstAccessTime()) + " , nextTimeWindow:"
                                + getReadableTime(callerContext.getNextTimeWindow()) + GatewayUtils.getThreadNameAndIdToLog());
                        log.trace("Caller=" + callerContext.getId() + " has reset counters and added for replication when unit "
                                + "time is over" + GatewayUtils.getThreadNameAndIdToLog());
                    }

                    if (callerContext.getGlobalCounter() <= maxRequest) {
                        canAccess = true;
                    }

                } else {
                    // if  caller in prohibit session  and prohibit period has not  over
                    if (log.isTraceEnabled()) {
                        String type = ThrottleConstants.IP_BASE == configuration.getType() ?
                                "IP address" : "domain";
                        log.trace("Even unit time has over , CallerContext in prohibit state :"
                                + type + " - " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
                    }
                }
            }

        }
        log.debug("$$$ In canAccessIfUnitTimeOver:  DECISION MADE. CAN ACCESS: " + canAccess +
                " request time:" + requestContext.getRequestTime() + "(" + getReadableTime(requestContext.getRequestTime()) + ")"
                + " firstAccessTime: (" + callerContext.getFirstAccessTime() + ")" + getReadableTime(callerContext.getFirstAccessTime()) +
                " callerContext.getNextTimeWindow():" + callerContext.getNextTimeWindow() + "(" + getReadableTime(callerContext.getNextTimeWindow())
                + ")" + " nextAccessTime:" + callerContext.getNextAccessTime() + "(" + getReadableTime(callerContext.getNextAccessTime()) + ")" + " localHits:" +
                callerContext.getLocalHits() + " globalHits :" + callerContext.getGlobalCounter() + " Thread name: " +
                Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
        return canAccess;
    }

    /**
     * Syncs the throttle window parameters
     *
     * @param callerContext
     * @param isInvocationFlow else is that the flow is just a syncing flow which doesn't increase counters
     */
    @Override
    public void syncThrottleCounterParams(CallerContext callerContext, boolean isInvocationFlow, RequestContext requestContext) {
        if (log.isTraceEnabled()) {
            log.trace("\n\n///////////////// &&& Running throttleCounterParamSync(). Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
        }
        synchronized (callerContext.getId().intern()) {
            /*if (!SharedParamManager.lockSharedKeys(callerContext.getId(), gatewayId)) {
                log.warn("Syncing throttle counter params skipped. Shared keys are locked. Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
                return;
            }*/
            long syncingStartTime = System.currentTimeMillis();
            if (log.isTraceEnabled()) {
                log.trace("CallerContext.getNextTimeWindow() :" + callerContext.getNextTimeWindow()
                        + "(" + getReadableTime(callerContext.getNextTimeWindow()) + ")" + " Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
            }

            if (callerContext.getNextTimeWindow() > requestContext.getRequestTime()) {
                if (log.isTraceEnabled()) {
                    log.trace("Running counter sync task");
                }
                String id = callerContext.getId();
                if (log.isTraceEnabled()) {
                    log.trace("### Initial Local counter:" + callerContext.getLocalCounter() + " , globalCounter:"
                            + callerContext.getGlobalCounter() + " distributedCounter :" + SharedParamManager.getDistributedCounter(id)
                            + " Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
                }

                if (isInvocationFlow) {
                    callerContext.incrementLocalCounter(); // increment local counter to consider current request
                }
                long localCounter = callerContext.getLocalCounter();
                if (log.isTraceEnabled()) {
                    log.trace("///////////////// $$$ 4.1 localCounter increased to:" + localCounter + " Thread name: "
                            + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
                }

                callerContext.resetLocalCounter();
                Long distributedCounter = SharedParamManager.addAndGetDistributedCounter(id, localCounter);

                if (log.isTraceEnabled()) {
                    log.trace("After calling addAndGetDistributedCounter, checking the shared counter availability now"
                            + GatewayUtils.getThreadNameAndIdToLog());
                    log.trace("///////////////// finally distributedCounter :" + distributedCounter
                            + GatewayUtils.getThreadNameAndIdToLog());
                }


                //Update instance's global counter value with distributed counter
                long x = callerContext.getGlobalCounter();
                //callerContext.setGlobalCounter(distributedCounter + localCounter);
                callerContext.setGlobalCounter(distributedCounter);
                if (log.isTraceEnabled()) {
                    log.trace("///////////////// &&&  4.2 finally globalCounter increased from:" + x + " to : "
                            + callerContext.getGlobalCounter() + GatewayUtils.getThreadNameAndIdToLog());
                    log.trace("///////////////// &&&  finally local counter reseted to 0\n" + GatewayUtils.getThreadNameAndIdToLog() + "\n");
                }

            } else {
                if (log.isTraceEnabled()) {
                    log.trace("Counter Sync task skipped" + GatewayUtils.getThreadNameAndIdToLog() + "\n");
                }
            }
            // SharedParamManager.releaseSharedKeys(callerContext.getId());
            log.debug("LATENCY FOR syncThrottleCounterParams: " + (System.currentTimeMillis() - syncingStartTime) +
                    " ms for callerContext: " + callerContext.getId() + " Thread name: " + Thread.currentThread().getName() +
                    " Thread id: " + Thread.currentThread().getId());

        }
    }

    @Override
    public void syncThrottleWindowParams(CallerContext callerContext, boolean isInvocationFlow) {
        synchronized (callerContext.getId().intern()) {
            long syncingStartTime = System.currentTimeMillis();
            log.trace("\n\n /////////////////  5 - Running throttleWindowParamSync" + GatewayUtils.getThreadNameAndIdToLog());

            // ThrottleWindowReplicator run() method
            String callerId = callerContext.getId();
            long sharedTimestamp = SharedParamManager.getSharedTimestamp(callerContext.getId());  // this will be set 0 if the redis key-value pair is not available
            log.trace("Got sharedTimestamp from redis. sharedTimestamp :" + sharedTimestamp + "(" + getReadableTime(sharedTimestamp) + ") "
                    + GatewayUtils.getThreadNameAndIdToLog());
            //1
            log.trace("TTL of sharedTimetamp:" + SharedParamManager.getTtl("startedTime-" + callerContext.getId())
                    + GatewayUtils.getThreadNameAndIdToLog());
            //2
            long sharedNextWindow = sharedTimestamp + callerContext.getUnitTime();
            long localFirstAccessTime = callerContext.getFirstAccessTime();

            log.trace("/////////////////   INITIAL ** sharedTimestamp :" + getReadableTime(sharedTimestamp) +
                    " sharedNextWindow :" + getReadableTime(sharedNextWindow) + " localFirstAccessTime :"
                    + getReadableTime(localFirstAccessTime) + "  callerContext.getUnitTime():" + callerContext.getUnitTime()
                    + GatewayUtils.getThreadNameAndIdToLog());

            long distributedCounter = SharedParamManager.getDistributedCounter(callerId);
            log.trace("Got distributedCounter from redis. distributedCounter :" + distributedCounter
                    + GatewayUtils.getThreadNameAndIdToLog());
            log.trace("///////////////// localCounter:" + callerContext.getLocalCounter() + ", globalCounter:"
                    + callerContext.getGlobalCounter() + ", localHits:" + callerContext.getLocalHits() + " Thread name: "
                    + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
            if (localFirstAccessTime < sharedTimestamp) {  // If this is a new time window. If a sync msg is received from another node, this will be true
                log.trace("///////////////// Hit if ***** A1" + GatewayUtils.getThreadNameAndIdToLog());
                log.trace("distributedCounter :" + distributedCounter);
                callerContext.setFirstAccessTime(sharedTimestamp);
                callerContext.setNextTimeWindow(sharedNextWindow);
                callerContext.setGlobalCounter(distributedCounter);
                if (!isInvocationFlow) {
                    callerContext.setLocalHits(0); // >> if localCounter was set 0 here, that premature throttling won't happen. But can't set 0 here too since then already recieved request that should
                    // be counted will be lost.
                }
                if (log.isTraceEnabled()) {
                    log.trace("///////////////// Setting time windows of caller context " + callerId
                            + " when window already set at another GW" + GatewayUtils.getThreadNameAndIdToLog());
                }
                //If some request comes to a nodes after some node set the shared timestamp then this
                // check whether the first access time of local is in between the global time window
                // if so this will set local caller context time window to global
            } else if (localFirstAccessTime == sharedTimestamp) { // if this node itself set the shared timestamp || or if another node-sent sync msg had triggered setting sharedTimestamp and sharedTimestampfrom that other node
                callerContext.setGlobalCounter(distributedCounter);
                log.trace("/////////////////&&&  localFirstAccessTime == sharedTimestamp" + GatewayUtils.getThreadNameAndIdToLog());
                log.trace("///////////////// &&&  - globalCounter :" + callerContext.getGlobalCounter() + GatewayUtils.getThreadNameAndIdToLog());
            } else if (localFirstAccessTime > sharedTimestamp    // if another node had set the shared timestamp, earlier
                    && localFirstAccessTime < sharedNextWindow) {
                log.trace("///////////////// Hit ELSE-IF**** A2" + GatewayUtils.getThreadNameAndIdToLog());

                callerContext.setFirstAccessTime(sharedTimestamp);
                callerContext.setNextTimeWindow(sharedNextWindow);
                log.trace("///////////////// &&&  - distributedCounter :" + distributedCounter + GatewayUtils.getThreadNameAndIdToLog());
                callerContext.setGlobalCounter(distributedCounter);
                if (log.isTraceEnabled()) {
                    log.trace("///////////////// Setting time windows of caller context in intermediate interval=" +
                            callerId + GatewayUtils.getThreadNameAndIdToLog());
                }
                log.trace("///////////////// &&&  - getGlobalCounter :" + callerContext.getGlobalCounter()
                        + GatewayUtils.getThreadNameAndIdToLog());
                //If above two statements not meets, this is the place where node set new window if
                // global first access time is 0, then it will be the beginning of the throttle time time
                // window so present node will set shared timestamp and the distributed counter. Also if time
                // window expired this will be the node who set the next time window starting time
            } else {
                log.trace("\n\n ///////////////// Hit Else**** A3" + GatewayUtils.getThreadNameAndIdToLog());  // In the flow this is the first time that reaches throttleWindowParamSync method. And then at canAccessIfUnitTimeOver flow, the first call after the sharedTimestamp is removed from redis. // seems this block is not setting shared values correctly in redis
                log.trace("\n\nCalling setSharedTimestamp" + GatewayUtils.getThreadNameAndIdToLog());
                //SharedParamManager.setSharedTimestamp(callerId, localFirstAccessTime);
                SharedParamManager.setSharedTimestampWithExpiry(callerId, localFirstAccessTime, callerContext.getUnitTime() + localFirstAccessTime);

                //3

                log.trace("\n\n Calling setDistributedCounter" + GatewayUtils.getThreadNameAndIdToLog());
                //4

                // log.trace( "Do getDistributedCounter to check whether the counter exists in redis. Result:" + SharedParamManager.getDistributedCounter(callerId));
                //SharedParamManager.setDistributedCounter(callerId, 0);
                SharedParamManager.setDistributedCounterWithExpiry(callerId, 0, callerContext.getUnitTime() + localFirstAccessTime);

                log.trace("Called setDistributedCounter. Setted value 0. " + GatewayUtils.getThreadNameAndIdToLog());
                //5

                //log.trace("\n\n Before calling setExpiryTime method");
                //      long sharedTimestamp2 = SharedParamManager.getSharedTimestamp(callerContext.getId());
                //      long sharedTimestamp3 = SharedParamManager.getDistributedCounter(callerContext.getId());

                //log.trace("\n\n Calling setExpiryTime");
//                SharedParamManager.setExpiryTime(callerId,
//                        callerContext.getUnitTime() + localFirstAccessTime); //
                //6
                //Reset global counter here as throttle replicator task may have updated global counter
                //with dirty value
                //resetGlobalCounter();
                //callerContext.setLocalCounter(1)
                //log.trace("///////////////// &&&  - after setting GlobalCounter :" + callerContext.getGlobalCounter());
                //setLocalCounter(1);//Local counter will be set to one as new time window starts
                if (log.isTraceEnabled()) {
                    log.trace("\n ///////////////// Completed resetting time window of=" + callerId + GatewayUtils.getThreadNameAndIdToLog());
                }
            }
            log.trace("///////////////// STWP: Method final: ** sharedTimestamp :"
                    + getReadableTime(SharedParamManager.getSharedTimestamp(callerId)) +
                    " sharedNextWindow :" + getReadableTime(sharedNextWindow) + " localFirstAccessTime :"
                    + getReadableTime(localFirstAccessTime) + GatewayUtils.getThreadNameAndIdToLog());
            //SharedParamManager.releaseSharedKeys(callerId);
            log.debug("LATENCY FOR syncThrottleWindowParams: " + (System.currentTimeMillis() - syncingStartTime) + " ms for callerContext: " + callerContext.getId() + GatewayUtils.getThreadNameAndIdToLog());
        }
    }

    /**
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

/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import org.apache.synapse.commons.throttle.core.DistributedCounterManager;
import org.wso2.carbon.apimgt.impl.dto.RedisConfig;
import redis.clients.jedis.*;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Redis Base Distributed Counter Manager for Throttler.
 */
public class RedisBaseDistributedCountManager implements DistributedCounterManager {

    private static final Log log = LogFactory.getLog(RedisBaseDistributedCountManager.class);
    JedisPool redisPool;
    long keyLockRetrievalTimeout;
   // String gatewayId;

    public RedisBaseDistributedCountManager(JedisPool redisPool) {
       // log.trace("### RedisBaseDistributedCountManager instantiated !!!");
        this.redisPool = redisPool;
        RedisConfig redisConfig = org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder.
                getInstance().getAPIManagerConfigurationService().getAPIManagerConfiguration().getRedisConfig();
        keyLockRetrievalTimeout = redisConfig.getKeyLockRetrievalTimeout();
      //  gatewayId = redisConfig.getGatewayId();

//        JedisPubSub jedisPubSub = new JedisPubSub() {
//
//            @Override
//            public void onSubscribe(String channel, int subscribedChannels) {
//                super.onSubscribe(channel, subscribedChannels);
//                log.trace("Client is Subscribed to " + channel);
//                log.trace("Client is Subscribed to "+ subscribedChannels + " no. of channels");
//            }
//        };
//
//       // this.redisPool.getResource().subscribe(jedisPubSub, "wso2_sync_mode_init");
//        Map<String, String> sync_mode_init_channel = this.redisPool.getResource().pubsubNumSub("sync_mode_init_channel");
//        log.trace("GGGGGG");
//        // iterate over the map entries
//        for (Map.Entry<String, String> entry : sync_mode_init_channel.entrySet()) {
//            // access each entry
//            String channel = entry.getKey();
//            String count = entry.getValue();
//            log.trace(">>> channel:" + channel + " count:" + count);
//        }

    }

    @Override
    public long getCounter(String key) {

        long startTime = 0;
        try {
            String count = null;
            startTime = System.currentTimeMillis();
            try (Jedis jedis = redisPool.getResource()) {
                Transaction transaction = jedis.multi();
                Response<String> response = transaction.get(key);
                transaction.exec();

                if (response != null && response.get() != null) {
                    count = response.get();
                }
                if (count != null) {
                    long l = Long.parseLong(count);
                    if (log.isDebugEnabled()) {
                        log.trace(String.format("%s Key exist in redis with value %s", key, l));
                    }
                    log.trace("RedisBaseDistributedCountManager*****.getCounter**1 Redis getCounter:" + l);
                    return l;
                } else {
                    log.trace(String.format("RedisBaseDistributedCountManager***** %s KEY DOES NOT EXIST !!!", key));
                }
                log.trace("shared counter key didn't exist. But returning:" + 0);
                return 0;
            }
        } finally {
            if (log.isDebugEnabled()) {
                //        log.trace("Time Taken to getDistributedCounter :" + (System.currentTimeMillis() - startTime));
            }
        }

    }

    @Override
    public void setCounter(String key, long value) {
        log.trace("Checking ttl before calling setCounter. TTL:" + getTtl(key));
        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            asyncGetAndAlterCounter(key, value); // this should remove the expiry time as new key is created by this
            log.trace("RedisBaseDistributedCountManager*****.setCounter ** : key:" + key + ", value:" + value);
        } finally {
            if (log.isDebugEnabled()) {
                //     log.trace("Time Taken to setDistributedCounter :" + (System.currentTimeMillis() - startTime));
            }

        }
        long xx = getCounter(key);
        log.trace("Key: " + key + " Set counter: at end check by getting the counter:" + xx +
                " Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId() + " TTL:" + getTtl(key));
    }

    @Override
    public long addAndGetCounter(String key, long value) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();
            try (Jedis jedis = redisPool.getResource()) {

                Transaction transaction = jedis.multi();
                Response<String> previousResponse = transaction.get(key);
                Response<Long> incrementedValueResponse = transaction.incrBy(key, value);
                transaction.exec();
                Long incrementedValue = incrementedValueResponse.get();
                if (log.isDebugEnabled()) {
                    log.trace(String.format("RedisBaseDistributedCountManager*****addAndGetCounter** %s Key increased from %s to %s", key, previousResponse.get(),
                            incrementedValue));
                }
                return incrementedValue;
            }

        } finally {
            if (log.isDebugEnabled()) {
                //      log.trace("Time Taken to addAndGetDistributedCounter :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

    @Override
    public void removeCounter(String key) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                Transaction transaction = jedis.multi();
                transaction.del(key);
                transaction.exec();
                if (log.isDebugEnabled()) {
                    log.trace(String.format("%s Key Removed", key));
                }
                log.trace("RedisBaseDistributedCountManager*****Counter Key Removed:" + key + " current timestamp:" + System.currentTimeMillis());
            }
        } finally {
            if (log.isDebugEnabled()) {
                log.trace("Time Taken to removeCounter :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

    @Override
    public long asyncGetAndAddCounter(String key, long value) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {
                long current = 0;
                Transaction transaction = jedis.multi();
                Response<String> currentValue = transaction.get(key);

                Response<Long> incrementedValue = transaction.incrBy(key, value);
                transaction.exec();
                if (currentValue != null && currentValue.get() != null) {
                    current = Long.parseLong(currentValue.get());
                }
                if (log.isDebugEnabled()) {
                    log.trace(String.format("RedisBaseDistributedCountManager*****asyncGetAndAddCounter** %s Key increased from %s to %s", key, current, incrementedValue.get()));
                }
                return current;
            }
        } finally {
            if (log.isDebugEnabled()) {
                //     log.trace("Time Taken to asyncGetAndAddDistributedCounter :" + (System.currentTimeMillis() - startTime));
            }
        }

    }

    @Override
    public long asyncAddCounter(String key, long value) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {
                long incrementedValue = 0;
                Transaction transaction = jedis.multi();

                Response<Long> responseValue = transaction.incrBy(key, value);
                transaction.exec();
                if (responseValue != null && responseValue.get() != null) {
                    incrementedValue = responseValue.get();
                }
                if (log.isDebugEnabled()) {
                    log.trace(String.format("RedisBaseDistributedCountManager*****asyncAddCounter** %s Key increased from %s to %s", key, incrementedValue - value, incrementedValue));
                }
                return incrementedValue;
            }
        } finally {
            if (log.isDebugEnabled()) {
                //     log.trace("Time Taken to asyncAddCounter :" + (System.currentTimeMillis() - startTime));
            }
        }

    }

    @Override
    public long asyncGetAndAlterCounter(String key, long value) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                long current = 0;
                Transaction transaction = jedis.multi();
                Response<String> currentValue = transaction.get(key);
                transaction.del(key);
                Response<Long> incrementedValue = transaction.incrBy(key, value);
                transaction.exec();

                if (currentValue != null && currentValue.get() != null) {
                    current = Long.parseLong(currentValue.get());
                }
                if (log.isDebugEnabled()) {
                    log.trace(String.format("RedisBaseDistributedCountManager*****asyncGetAndAlterCounter %s Key increased from %s to %s", key, current, incrementedValue.get()));
                }
                return current;
            }
        } finally {
            if (log.isDebugEnabled()) {
                //     log.trace("Time Taken to asyncGetAndAlterDistributedCounter :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

    @Override
    public long getTimestamp(String key) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {
                Transaction transaction = jedis.multi();
                Response<String> response = transaction.get(key);
                transaction.exec();

                if (response != null && response.get() != null) {
                    log.trace("RedisBaseDistributedCountManager*****getTimestamp. key:" + key + ". Timestamp not null ** getTimestamp:" + getReadableTime(Long.parseLong(response.get())) + "(" + response.get() + ")");

                    return Long.parseLong(response.get());

                } else {
                    log.trace("RedisBaseDistributedCountManager*****TIMESTAMP NOT EXIST !!!. key: " + key + "  So set to 0. ** :");
                }
                return 0;
            }
        } finally {
            if (log.isDebugEnabled()) {
                //  log.trace("Time Taken to getSharedTimestamp :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

    /*
    @Override
    public long getTimestamp(String key) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                String timeStamp = jedis.get(key);
                if (timeStamp != null) {
                    log.trace("getTimestamp. key:" + key + ". Timestamp not null ** getTimestamp:" + getReadableTime(Long.parseLong(timeStamp)) + "(" + timeStamp + ")");
                    return Long.parseLong(timeStamp);
                } else {
                    log.trace("getTimestamp :" + key + ". Timestamp null. So set to 0. ** :");
                }
                return 0;
            }
        } finally {
            if (log.isDebugEnabled()) {
              //  log.trace("Time Taken to getSharedTimestamp :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

     */

    @Override
    public void setTimestamp(String key, long timeStamp) {
        log.trace("Checking ttl before calling timestamp. TTL:" + getTtl(key));
        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                Transaction transaction = jedis.multi();
                transaction.set(key, String.valueOf(timeStamp));
                transaction.exec();
                log.trace("RedisBaseDistributedCountManager*****.setTimestamp***** " + getReadableTime(timeStamp) + "(" +
                        timeStamp + ").  Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
            }
        } finally {
            if (log.isDebugEnabled()) {
                //   log.trace("Time Taken to setTimestamp :" + (System.currentTimeMillis() - startTime));
            }
        }
        log.trace("key:" + key + "  After setting timestamp .getTimestamp : " + getTimestamp(key) + " TTL:" + getTtl(key));
    }

    @Override
    public void removeTimestamp(String key) {

        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                Transaction transaction = jedis.multi();
                transaction.del(key);
                transaction.exec();
                log.trace("RedisBaseDistributedCountManager*****shared timestamp key removed key : " + key + "current timestamp:" + System.currentTimeMillis());

            }
        } finally {
            if (log.isDebugEnabled()) {
                log.trace("Time Taken to removeTimestamp :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

//    @Override
//    public long getExpiry(String key) {
//        long startTime = 0;
//        try {
//            try (Jedis jedis = redisPool.getResource()) {
//                long expiryTime = jedis.expireTime(key);
//                log.trace("(negative value is returned in order to signal an error: -1 if the key exists but has no associated expiration time, and -2 if the key does not exist.)");
//                return expiryTime;
//            }
//        } finally {
//            if (log.isDebugEnabled()) {
//                log.trace("Time Taken to getExpiry :" + (System.currentTimeMillis() - startTime));
//            }
//        }
//
//    }


    @Override
    public void setExpiry(String key, long expiryTimeStamp) {
        long currentTime = System.currentTimeMillis();

        log.trace("\n\n setting expiry of key :" + key + " to:" + getReadableTime(expiryTimeStamp) + " (" + expiryTimeStamp + ")" + " current timestamp:" +
                getReadableTime(currentTime) + " (" + currentTime + ")" + "  Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());

        long startTime = 0;

        log.trace("Initially Checking if the key:" + key + " exists");
        if (key.startsWith("sharedCounter")) {
            getCounter(key);
        } else if (key.startsWith("startedTime")) {
            getTimestamp(key);
        } else {
            log.trace("key:" + key + " does not start with sharedCounter or startedTime");
        }


        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {
                Transaction transaction = jedis.multi();
                Response<Long> x = transaction.pexpireAt(key, expiryTimeStamp);
                transaction.exec();
//                log.trace("RedisBaseDistributedCountManager.setExpiry state " + x.get() + " of key:" + key +
//                        "  (1 if the pexpire was set, 0 if the timeout was not set. e.g. key doesn't exist, " +
//                        "or operation skipped due to the provided arguments.)");
                if (x.get() == 1) {
                    log.trace("pexpire timeout was set. state " + x.get() + " of key:" + key);
                } else if (x.get() == 0) {
                    log.trace("pexpire timeout was not set. state: " + x.get() + " of key:" + key + " e.g. key doesn't exist, or operation skipped due to the provided arguments.");
                } else {
                    log.trace("pexpire timeout was not set. else value");
                }
            }
        } finally {
            if (log.isDebugEnabled()) {
                //  log.trace("Time Taken to setExpiry :" + (System.currentTimeMillis() - startTime));
            }
        }

        log.trace("RedisBaseDistributedCountManager*****After setting expiry Checking the TTL of the key:" + key + " . TTL : " + getTtl(key));

        log.trace("\nAfter setting expiry Checking if the key:" + key + " exists");
        if (key.startsWith("sharedCounter")) {
            getCounter(key);
        } else if (key.startsWith("startedTime")) {
            getTimestamp(key);
        } else {
            log.trace("key:" + key + " does not start with sharedCounter or sharedTimestamp");
        }

        log.trace("Again at the end of setExpiry() method checking the TTL of key " + key + " . TTL : " + getTtl(key));
        log.trace("\n");

    }

    public long getTtl(String key) {
        long ttl = 0;
        try {
            try (Jedis jedis = redisPool.getResource()) {
                Transaction transaction = jedis.multi();
                Response<Long> pttl = transaction.pttl(key);
                transaction.exec();
                ttl = pttl.get();
                if (ttl == -2) {
                    log.trace("RedisBaseDistributedCountManager*****TTL of key :" + key + " : " + ttl + " (Key does not exists)");
                } else if (ttl == -1) {
                    log.trace("RedisBaseDistributedCountManager*****TTL of key :" + key + " : " + ttl + " (Key does not have an associated expire)");
                }
                return ttl;
            }
        } finally {
            if (log.isDebugEnabled()) {

            }
        }
    }

    public long setLock(String key, String value) {
        log.trace("Checking ttl before calling ..:" + getTtl(key));
        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                Transaction transaction = jedis.multi();
                Response<Long> response = transaction.setnx(key, value);
                transaction.exec();
                long responseCode = response.get();
                if (responseCode == 1) {
                    log.trace("Key was set");
                } else if (responseCode == 0) {
                    log.trace("Key was not set. It is already available");

                }
                log.trace("RedisBaseDistributedCountManager*****.setLock*****  Thread name:" + Thread.currentThread().getName() + " Thread id: " + Thread.currentThread().getId());
                return responseCode;
            }
        } finally {
            if (log.isDebugEnabled()) {
                //   log.trace("Time Taken to setLock :" + (System.currentTimeMillis() - startTime));
            }
        }
    }

    @Override

    public boolean isEnable() {

        return true;
    }

    @Override
    public String getType() {

        return "redis";
    }

    public String getReadableTime(long time) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
        Date date = new Date(time);
        String formattedTime = dateFormat.format(date);
        return formattedTime;
    }

    @Override
    public long getKeyLockRetrievalTimeout() {
        return keyLockRetrievalTimeout;
    }

    @Override
    public void removeLock(String key) {
        long startTime = 0;
        try {
            startTime = System.currentTimeMillis();

            try (Jedis jedis = redisPool.getResource()) {

                Transaction transaction = jedis.multi();
                transaction.del(key);
                transaction.exec();
            }
        } finally {
            if (log.isDebugEnabled()) {
                log.trace("Time Taken to remove lock :" + (System.currentTimeMillis() - startTime));
            }
        }
    }
}


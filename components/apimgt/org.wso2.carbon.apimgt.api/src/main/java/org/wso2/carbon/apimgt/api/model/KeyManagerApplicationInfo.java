package org.wso2.carbon.apimgt.api.model;

public class KeyManagerApplicationInfo {
    String KeyManagerName;
    String ConsumerKey;

    public String getKeyManagerName() {
        return KeyManagerName;
    }

    public void setKeyManagerName(String keyManagerName) {
        KeyManagerName = keyManagerName;
    }

    public String getConsumerKey() {
        return ConsumerKey;
    }

    public void setConsumerKey(String consumerKey) {
        ConsumerKey = consumerKey;
    }
}

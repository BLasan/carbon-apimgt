package org.wso2.carbon.apimgt.api.model;

public class KeyManagerApplicationInfo {
    String keyManagerName;
    String consumerKey;
    String mode;

    public String getKeyManagerName() {
        return keyManagerName;
    }

    public void setKeyManagerName(String keyManagerName) {
        this.keyManagerName = keyManagerName;
    }

    public String getConsumerKey() {
        return consumerKey;
    }

    public void setConsumerKey(String consumerKey) {
        this.consumerKey = consumerKey;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}

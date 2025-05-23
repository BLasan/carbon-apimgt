/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.wso2.carbon.apimgt.common.analytics;

/**
 * analytics related publisher constants.
 */
public class Constants {

    public static final String RESPONSE_METRIC_NAME = "apim:response";
    public static final String FAULTY_METRIC_NAME = "apim:faulty";

    public static final String ANONYMOUS_VALUE = "anonymous";
    public static final String UNKNOWN_VALUE = "UNKNOWN";
    public static final int UNKNOWN_INT_VALUE = -1;
    public static final String IPV4_PROP_TYPE = "IPV4";
    public static final String IPV6_PROP_TYPE = "IPV6";
    public static final String EMAIL_PROP_TYPE = "EMAIL";
    public static final String USERNAME_PROP_TYPE = "USERNAME";

    public static final String IPV4_MASK_VALUE = "***";
    public static final String IPV6_MASK_VALUE = "**";
    public static final String EMAIL_MASK_VALUE = "*****";
    public static final String USERNAME_MASK_VALUE = "*****";

    public static final String AUTH_API_URL = "auth.api.url";

    public static final String CHOREO_REPORTER_NAME = "choreo";
}

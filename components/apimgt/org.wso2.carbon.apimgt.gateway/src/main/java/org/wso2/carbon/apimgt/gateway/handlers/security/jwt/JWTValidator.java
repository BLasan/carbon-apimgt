/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.apimgt.gateway.handlers.security.jwt;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.util.DateUtils;
import org.apache.axis2.Constants;
import org.apache.axis2.util.JavaUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.RESTConstants;
import org.json.JSONObject;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.KeyManager;
import org.wso2.carbon.apimgt.common.gateway.constants.GraphQLConstants;
import org.wso2.carbon.apimgt.common.gateway.dto.JWTInfoDto;
import org.wso2.carbon.apimgt.common.gateway.dto.JWTValidationInfo;
import org.wso2.carbon.apimgt.common.gateway.exception.JWTGeneratorException;
import org.wso2.carbon.apimgt.common.gateway.jwtgenerator.AbstractAPIMgtGatewayJWTGenerator;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.MethodStats;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.gateway.handlers.security.APIKeyValidator;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.handlers.streaming.websocket.WebSocketApiConstants;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.gateway.jwt.RevokedJWTDataHolder;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.caching.CacheProvider;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.ExtendedJWTConfigurationDto;
import org.wso2.carbon.apimgt.impl.factory.KeyManagerHolder;
import org.wso2.carbon.apimgt.impl.jwt.JWTValidationService;
import org.wso2.carbon.apimgt.impl.jwt.SignedJWTInfo;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.JWTUtil;
import org.wso2.carbon.apimgt.impl.utils.SigningUtil;
import org.wso2.carbon.apimgt.keymgt.service.TokenValidationContext;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;

import java.security.cert.Certificate;
import java.text.ParseException;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.cache.Cache;

/**
 * A Validator class to validate JWT tokens in an API request.
 */
public class JWTValidator {

    private static final Log log = LogFactory.getLog(JWTValidator.class);
    private boolean isGatewayTokenCacheEnabled;
    private APIKeyValidator apiKeyValidator;
    private boolean jwtGenerationEnabled;
    private AbstractAPIMgtGatewayJWTGenerator apiMgtGatewayJWTGenerator;
    private Set<String> audiences;
    ExtendedJWTConfigurationDto jwtConfigurationDto;
    JWTValidationService jwtValidationService;
    private static volatile long ttl = -1L;

    public JWTValidator(APIKeyValidator apiKeyValidator, String tenantDomain) throws APIManagementException {
        int tenantId = APIUtil.getTenantIdFromTenantDomain(tenantDomain);
        this.isGatewayTokenCacheEnabled = GatewayUtils.isGatewayTokenCacheEnabled();
        this.apiKeyValidator = apiKeyValidator;
        this.jwtConfigurationDto =
                ServiceReferenceHolder.getInstance().getAPIManagerConfiguration().getJwtConfigurationDto();
        jwtGenerationEnabled = jwtConfigurationDto.isEnabled();
        apiMgtGatewayJWTGenerator =
                ServiceReferenceHolder.getInstance().getApiMgtGatewayJWTGenerator()
                        .get(this.jwtConfigurationDto.getGatewayJWTGeneratorImpl());
        if (jwtGenerationEnabled) {
            // Set certificate to jwtConfigurationDto
            if (jwtConfigurationDto.isTenantBasedSigningEnabled()) {
                this.jwtConfigurationDto.setPublicCert(SigningUtil.getPublicCertificate(tenantId));
                this.jwtConfigurationDto.setPrivateKey(SigningUtil.getSigningKey(tenantId));
            } else {
                this.jwtConfigurationDto.setPublicCert(ServiceReferenceHolder.getInstance().getPublicCert());
                this.jwtConfigurationDto.setPrivateKey(ServiceReferenceHolder.getInstance().getPrivateKey());
            }

            // Set private key to jwtConfigurationDto

            // Set ttl to jwtConfigurationDto
            this.jwtConfigurationDto.setTtl(getTtl());

            //setting the jwt configuration dto
            apiMgtGatewayJWTGenerator.setJWTConfigurationDto(this.jwtConfigurationDto);
        }

        jwtValidationService = ServiceReferenceHolder.getInstance().getJwtValidationService();
    }

    protected JWTValidator(String apiLevelPolicy, boolean isGatewayTokenCacheEnabled,
                           APIKeyValidator apiKeyValidator, boolean jwtGenerationEnabled,
                           AbstractAPIMgtGatewayJWTGenerator apiMgtGatewayJWTGenerator,
                           ExtendedJWTConfigurationDto jwtConfigurationDto,
                           JWTValidationService jwtValidationService) {

        this.isGatewayTokenCacheEnabled = isGatewayTokenCacheEnabled;
        this.apiKeyValidator = apiKeyValidator;
        this.jwtGenerationEnabled = jwtGenerationEnabled;
        this.apiMgtGatewayJWTGenerator = apiMgtGatewayJWTGenerator;
        this.jwtConfigurationDto = jwtConfigurationDto;
        this.jwtValidationService = jwtValidationService;
    }

    public JWTValidator(APIKeyValidator apiKeyValidator, String tenantDomain, Set<String> audiences)
            throws APIManagementException {
        this(apiKeyValidator, tenantDomain);
        this.setAudiences(audiences);
    }

    /**
     * Authenticates the given request with a JWT token to see if an API consumer is allowed to access
     * a particular API or not.
     *
     * @param signedJWTInfo The JWT token sent with the API request
     * @param synCtx   The message to be authenticated
     * @return an AuthenticationContext object which contains the authentication information
     * @throws APISecurityException in case of authentication failure
     */
    @MethodStats
    public AuthenticationContext authenticate(SignedJWTInfo signedJWTInfo, MessageContext synCtx)
            throws APISecurityException {

        String apiContext = (String) synCtx.getProperty(RESTConstants.REST_API_CONTEXT);
        String apiVersion = (String) synCtx.getProperty(RESTConstants.SYNAPSE_REST_API_VERSION);


        org.apache.axis2.context.MessageContext axis2MsgContext =
                ((Axis2MessageContext) synCtx).getAxis2MessageContext();
        String httpMethod = (String) axis2MsgContext.getProperty(Constants.Configuration.HTTP_METHOD);
        Boolean disableCNFValidation = (Boolean) axis2MsgContext.getProperty(
                APISecurityConstants.DISABLE_CNF_VALIDATION);
        String matchingResource = (String) synCtx.getProperty(APIConstants.API_ELECTED_RESOURCE);
        String jwtTokenIdentifier = getJWTTokenIdentifier(signedJWTInfo);
        String jwtHeader = signedJWTInfo.getSignedJWT().getHeader().toString();

        // Check for CNF validation
        if (!isCNFValidationDisabled(disableCNFValidation, false)) {
            try {
                Certificate clientCertificate = Utils.getClientCertificate(axis2MsgContext);
                String cachedClientCertHash = signedJWTInfo.getClientCertificateHash();
                signedJWTInfo.setClientCertificate(clientCertificate);
                if (cachedClientCertHash != null) {
                    // If cachedClientCertHash is not null, the signedJWTInfo object is obtained from the cache. This
                    // means a request has been sent previously and the signedJWTInfo resultant object has been stored
                    // in the cache.
                    if (!cachedClientCertHash.equals(signedJWTInfo.getClientCertificateHash())) {
                        // This scenario can happen when the previous request and the current request contains two
                        // different certificates. In such a scenario, we cannot guarantee the validationStatus
                        // signedJWTInfo object obtained from the cache to be correct. Hence, the validationStatus of
                        // the signedJWTInfo is set to NOT_VALIDATED so that the JWT token will be validated again.
                        signedJWTInfo.setValidationStatus(SignedJWTInfo.ValidationStatus.NOT_VALIDATED);
                    }
                } else if (signedJWTInfo.getClientCertificateHash() != null) {
                    // This scenario can happen in two different instances.
                    // 1. When the signedJWTInfo object is not obtained from the cache and the current request contains
                    //    a certificate in the request header. This scenario depicts a situation where the JWT has not
                    //    been validated yet. Hence, the validationStatus of the signedJWTInfo is set to NOT_VALIDATED.
                    // 2. When the signedJWTInfo object is obtained from the cache (cachedClientCertHash becomes null
                    //    when the previous request do not contain a certificate in the request header) and the current
                    //    request contains a certificate in the request header. In such a scenario, we cannot guarantee
                    //    the validationStatus signedJWTInfo object as the certificate has not been validated. Hence,
                    //    the validationStatus of the signedJWTInfo is set to NOT_VALIDATED so that the JWT token will
                    //    be validated again.
                    signedJWTInfo.setValidationStatus(SignedJWTInfo.ValidationStatus.NOT_VALIDATED);
                }
            } catch (APIManagementException e) {
                log.error("Error while obtaining client certificate. " + GatewayUtils.getMaskedToken(jwtHeader));
            }
        }

        boolean includeTokenInfoInMsgCtx = Boolean.parseBoolean(
                System.getProperty(APIMgtGatewayConstants.INCLUDE_TOKEN_INFO_IN_MSG_CTX));

        if (StringUtils.isNotEmpty(jwtTokenIdentifier)) {
            if (RevokedJWTDataHolder.isJWTTokenSignatureExistsInRevokedMap(jwtTokenIdentifier)) {
                if (log.isDebugEnabled()) {
                    log.debug("Token retrieved from the revoked jwt token map. Token: " + GatewayUtils.
                            getMaskedToken(jwtHeader));
                }
                log.error("Invalid JWT token. " + GatewayUtils.getMaskedToken(jwtHeader));
                if (includeTokenInfoInMsgCtx) {
                    synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
                }
                throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS,
                        "Invalid JWT token");
            }
        }
        Object authorizedPartyClaim = signedJWTInfo.getJwtClaimsSet().getClaim(APIMgtGatewayConstants.AZP_JWT_CLAIM);
        Object entityIdClaim = signedJWTInfo.getJwtClaimsSet().getClaim(APIMgtGatewayConstants.ENTITY_ID_JWT_CLAIM);
        long jwtGeneratedTime = 0;
        try {
            jwtGeneratedTime = signedJWTInfo.getSignedJWT().getJWTClaimsSet().getIssueTime().getTime();
        } catch (ParseException e) {
            log.error("Error while obtaining JWT token generated time " + GatewayUtils.getMaskedToken(jwtHeader));
        }
        if (jwtGeneratedTime != 0 && authorizedPartyClaim != null && entityIdClaim != null) {
            String authorizedParty = (String) authorizedPartyClaim;
            String entityId = (String) entityIdClaim;
            if (RevokedJWTDataHolder.getInstance().isRevokedConsumerKeyExists(authorizedParty, jwtGeneratedTime)) {
                if (log.isDebugEnabled()) {
                    log.debug("Consumer key retrieved from the  jwt token map is in revoked consumer key map."
                            + " Token: " + GatewayUtils.getMaskedToken(jwtHeader));
                }
                log.error("Invalid JWT token. " + GatewayUtils.getMaskedToken(jwtHeader));
                if (includeTokenInfoInMsgCtx) {
                    synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
                }
                throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS,
                        "Invalid JWT token");
            }
            if (StringUtils.equals(entityId, authorizedParty)
                    && RevokedJWTDataHolder.getInstance().isRevokedSubjectEntityConsumerAppExists(
                            entityId, jwtGeneratedTime)) {
                // handle user event revocations of app tokens since the 'sub' claim is client id
                if (log.isDebugEnabled()) {
                    log.debug("Consumer key retrieved from the  jwt token map is in revoked consumer key map."
                            + " Token: " + GatewayUtils.getMaskedToken(jwtHeader));
                }
                log.error("Invalid JWT token. " + GatewayUtils.getMaskedToken(jwtHeader));
                if (includeTokenInfoInMsgCtx) {
                    synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
                }
                throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS, "Invalid JWT token");
            }
            if (!StringUtils.equals(entityId, authorizedParty) && RevokedJWTDataHolder.getInstance()
                    .isRevokedSubjectEntityUserExists(entityId, jwtGeneratedTime)) {
                if (log.isDebugEnabled()) {
                    log.debug("User id retrieved from the  jwt token map is in revoked user id map."
                            + " Token: " + GatewayUtils.getMaskedToken(jwtHeader));
                }
                log.error("Invalid JWT token. " + GatewayUtils.getMaskedToken(jwtHeader));
                if (includeTokenInfoInMsgCtx) {
                    synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
                }
                throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS,
                        "Invalid JWT token");
            }
        }
        JWTValidationInfo jwtValidationInfo = getJwtValidationInfo(signedJWTInfo, jwtTokenIdentifier);

        if (jwtValidationInfo != null) {
            if (jwtValidationInfo.isValid()) {

                // Validate subscriptions
                APIKeyValidationInfoDTO apiKeyValidationInfoDTO;

                log.debug("Begin subscription validation via Key Manager: " + jwtValidationInfo.getKeyManager());
                apiKeyValidationInfoDTO = validateSubscriptionUsingKeyManager(synCtx, jwtValidationInfo);
                synCtx.setProperty(
                        APIMgtGatewayConstants.APPLICATION_NAME, apiKeyValidationInfoDTO.getApplicationName()
                );
                synCtx.setProperty(APIMgtGatewayConstants.END_USER_NAME, apiKeyValidationInfoDTO.getEndUserName());

                if (log.isDebugEnabled()) {
                    log.debug("Subscription validation via Key Manager. Status: "
                            + apiKeyValidationInfoDTO.isAuthorized());
                }
                if (!apiKeyValidationInfoDTO.isAuthorized()) {
                    log.debug(
                            "User is NOT authorized to access the Resource. API Subscription validation failed.");
                    if (includeTokenInfoInMsgCtx) {
                        synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
                    }
                    throw new APISecurityException(apiKeyValidationInfoDTO.getValidationStatus(),
                            "User is NOT authorized to access the Resource. API Subscription validation failed.");

                }
                // Validate scopes
                validateScopes(apiContext, apiVersion, matchingResource, httpMethod, jwtValidationInfo, signedJWTInfo,
                        synCtx, includeTokenInfoInMsgCtx);
                validateAudiences(signedJWTInfo);
                synCtx.setProperty(APIMgtGatewayConstants.SCOPES, jwtValidationInfo.getScopes().toString());
                synCtx.setProperty(APIMgtGatewayConstants.JWT_CLAIMS, jwtValidationInfo.getClaims());
                if (apiKeyValidationInfoDTO.isAuthorized()) {
                    /*
                     * Set api.ut.apiPublisher of the subscribed api to the message context.
                     * This is necessary for the functionality of Publisher alerts.
                     * Set API_NAME of the subscribed api to the message context.
                     * */
                    synCtx.setProperty(APIMgtGatewayConstants.API_PUBLISHER, apiKeyValidationInfoDTO.getApiPublisher());
                    synCtx.setProperty("API_NAME", apiKeyValidationInfoDTO.getApiName());
                    /* GraphQL Query Analysis Information */
                    if (APIConstants.GRAPHQL_API.equals(synCtx.getProperty(APIConstants.API_TYPE))) {
                        synCtx.setProperty(GraphQLConstants.MAXIMUM_QUERY_DEPTH,
                                apiKeyValidationInfoDTO.getGraphQLMaxDepth());
                        synCtx.setProperty(GraphQLConstants.MAXIMUM_QUERY_COMPLEXITY,
                                apiKeyValidationInfoDTO.getGraphQLMaxComplexity());
                    }
                    log.debug("JWT authentication successful.");
                }
                log.debug("JWT authentication successful.");
                String endUserToken = null;
                if (jwtGenerationEnabled) {
                    JWTInfoDto jwtInfoDto = GatewayUtils
                            .generateJWTInfoDto(null, jwtValidationInfo, apiKeyValidationInfoDTO, synCtx);
                    endUserToken = generateAndRetrieveJWTToken(jwtTokenIdentifier, jwtInfoDto);
                }
                return GatewayUtils.generateAuthenticationContext(jwtTokenIdentifier, jwtValidationInfo, apiKeyValidationInfoDTO,
                        endUserToken, true);
            } else {
                if (includeTokenInfoInMsgCtx) {
                    if (jwtValidationInfo.isExpired()) {
                        synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token expired");
                    } else {
                        synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
                    }
                }
                throw new APISecurityException(jwtValidationInfo.getValidationCode(),
                        APISecurityConstants.getAuthenticationFailureMessage(jwtValidationInfo.getValidationCode()));
            }
        } else {
            if (includeTokenInfoInMsgCtx) {
                synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
            }
            throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                    APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE);
        }
    }

    private long getTtl() {
        if (ttl != -1) {
            return ttl;
        }
        synchronized (AbstractAPIMgtGatewayJWTGenerator.class) {
            if (ttl != -1) {
                return ttl;
            }
            APIManagerConfiguration config = org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder.getInstance().
                    getAPIManagerConfigurationService().getAPIManagerConfiguration();

            String gwTokenCacheConfig = config.getFirstProperty(APIConstants.GATEWAY_TOKEN_CACHE_ENABLED);
            boolean isGWTokenCacheEnabled = Boolean.parseBoolean(gwTokenCacheConfig);

            if (isGWTokenCacheEnabled) {
                String apimKeyCacheExpiry = config.getFirstProperty(APIConstants.TOKEN_CACHE_EXPIRY);

                if (apimKeyCacheExpiry != null) {
                    ttl = Long.parseLong(apimKeyCacheExpiry);
                } else {
                    ttl = Long.valueOf(900);
                }
            } else {
                String ttlValue = config.getFirstProperty(APIConstants.JWT_EXPIRY_TIME);
                if (ttlValue != null) {
                    ttl = Long.parseLong(ttlValue);
                } else {
                    //15 * 60 (convert 15 minutes to seconds)
                    ttl = Long.valueOf(900);
                }
            }
            return ttl;
        }
    }

    public Set<String> getAudiences() {

        return audiences;
    }

    public void setAudiences(Set<String> audiences) {

        this.audiences = audiences;
    }

    private boolean validateAudiences(SignedJWTInfo signedJWTInfo) throws APISecurityException {
        if (this.getAudiences() == null || this.getAudiences().isEmpty() ||
                this.getAudiences().contains(APIConstants.ALL_AUDIENCES)) {
            return true;
        }
        List<String> jwtAudienceClaim = signedJWTInfo.getJwtClaimsSet().getAudience();
        if (jwtAudienceClaim == null) {
            log.debug("User is NOT authorized to access the Resource. Audience validation failed.");
            throw new APISecurityException(APISecurityConstants.API_OAUTH_INVALID_AUDIENCES,
                    APISecurityConstants.API_OAUTH_INVALID_AUDIENCES_MESSAGE,
                                           APISecurityConstants.API_OAUTH_INVALID_AUDIENCES_DESCRIPTION);
        }
        for (String aud : this.getAudiences()) {
            if (jwtAudienceClaim.contains(aud)) {
                return true;
            }
        }
        log.debug("User is NOT authorized to access the Resource. Audience validation failed.");
        throw new APISecurityException(APISecurityConstants.API_OAUTH_INVALID_AUDIENCES,
                APISecurityConstants.API_OAUTH_INVALID_AUDIENCES_MESSAGE,
                                       APISecurityConstants.API_OAUTH_INVALID_AUDIENCES_DESCRIPTION);
    }

    private String generateAndRetrieveJWTToken(String tokenSignature, JWTInfoDto jwtInfoDto)
            throws APISecurityException {

        String endUserToken = null;
        boolean valid = false;
        String jwtTokenCacheKey = jwtInfoDto.getApiContext().concat(":").concat(jwtInfoDto.getVersion()).concat(":")
                .concat(tokenSignature);
        if (isGatewayTokenCacheEnabled) {
            Object token = getGatewayJWTTokenCache().get(jwtTokenCacheKey);
            if (token != null) {
                endUserToken = (String) token;
                long timestampSkew = getTimeStampSkewInSeconds() * 1000;
                valid = JWTUtil.isJWTValid(endUserToken, jwtConfigurationDto.getJwtDecoding(), timestampSkew);
            }
            if (StringUtils.isEmpty(endUserToken) || !valid) {
                try {
                    includeUserStoreClaimsIntoClaims(jwtInfoDto);
                    endUserToken = apiMgtGatewayJWTGenerator.generateToken(jwtInfoDto);
                    getGatewayJWTTokenCache().put(jwtTokenCacheKey, endUserToken);
                } catch (JWTGeneratorException e) {
                    log.error("Error while Generating Backend JWT", e);
                    throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                            APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE, e);
                }
            }
        } else {
            try {
                includeUserStoreClaimsIntoClaims(jwtInfoDto);
                endUserToken = apiMgtGatewayJWTGenerator.generateToken(jwtInfoDto);
            } catch (JWTGeneratorException e) {
                log.error("Error while Generating Backend JWT", e);
                throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                        APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE, e);
            }
        }
        return endUserToken;
    }

    private void includeUserStoreClaimsIntoClaims(JWTInfoDto jwtInfoDto) {

        JWTInfoDto localJWTInfoDto = new JWTInfoDto(jwtInfoDto);
        Map<String, String> userClaimsFromKeyManager = getUserClaimsFromKeyManager(localJWTInfoDto);
        JWTValidationInfo jwtValidationInfo = localJWTInfoDto.getJwtValidationInfo();
        if (jwtValidationInfo != null && jwtValidationInfo.getClaims() != null) {
            jwtValidationInfo.getClaims().putAll(userClaimsFromKeyManager);
        }
    }

    private APIKeyValidationInfoDTO validateSubscriptionUsingKeyManager(MessageContext synCtx,
                                                                        JWTValidationInfo jwtValidationInfo)
            throws APISecurityException {

        String apiContext = (String) synCtx.getProperty(RESTConstants.REST_API_CONTEXT);
        String apiVersion = (String) synCtx.getProperty(RESTConstants.SYNAPSE_REST_API_VERSION);
        return validateSubscriptionUsingKeyManager(apiContext, apiVersion, jwtValidationInfo);
    }

    private APIKeyValidationInfoDTO validateSubscriptionUsingKeyManager(String apiContext, String apiVersion,
                                                                        JWTValidationInfo jwtValidationInfo)
            throws APISecurityException {

        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();

        String consumerKey = jwtValidationInfo.getConsumerKey();
        String keyManager = jwtValidationInfo.getKeyManager();
        if (consumerKey != null && keyManager != null) {
            return apiKeyValidator.validateSubscription(apiContext, apiVersion, consumerKey, tenantDomain, keyManager);
        }
        log.debug("Cannot call Key Manager to validate subscription. " +
                "Payload of the token does not contain the Authorized party - the party to which the ID Token was " +
                "issued");
        throw new APISecurityException(APISecurityConstants.API_AUTH_FORBIDDEN,
                APISecurityConstants.API_AUTH_FORBIDDEN_MESSAGE);
    }

    /**
     * Validates token for Websocket requests.
     *
     * @param signedJWTInfo  SignedJWT Info
     * @param jti            JTI
     * @return JWT Validation Info
     * @throws APISecurityException If an error occurs
     */
    private JWTValidationInfo validateTokenForWS(SignedJWTInfo signedJWTInfo, String jti)
            throws APISecurityException {

        JWTValidationInfo jwtValidationInfo;
        String jwtHeader = signedJWTInfo.getSignedJWT().getHeader().toString();
        jwtValidationInfo = getJwtValidationInfo(signedJWTInfo, jti);
        if (RevokedJWTDataHolder.isJWTTokenSignatureExistsInRevokedMap(jti)) {
            if (log.isDebugEnabled()) {
                log.debug("Token retrieved from the revoked jwt token map. Token: " + GatewayUtils.
                        getMaskedToken(jwtHeader));
            }
            log.error("Invalid JWT token. " + GatewayUtils.getMaskedToken(jwtHeader));
            jwtValidationInfo.setValidationCode(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS);
            jwtValidationInfo.setValid(false);
        }
        return jwtValidationInfo;
    }

    /**
     * This method is used to validate subscriptions for WS API requests.
     *
     * @param jwtValidationInfo JWTValidationInfo
     * @param apiContext        API Context
     * @param apiVersion        API Version
     * @return APIKeyValidationInfoDTO
     * @throws APISecurityException if an error occurs.
     */
    private APIKeyValidationInfoDTO validateSubscriptionsForWS(JWTValidationInfo jwtValidationInfo, String apiContext, String apiVersion)
            throws APISecurityException {

        log.debug("Begin subscription validation via Key Manager: " + jwtValidationInfo.getKeyManager());
        APIKeyValidationInfoDTO apiKeyValidationInfoDTO = validateSubscriptionUsingKeyManager(apiContext,
                apiVersion, jwtValidationInfo);
        if (log.isDebugEnabled()) {
            log.debug("Subscription validation via Key Manager: " + jwtValidationInfo.getKeyManager() + ". Status: " +
                    apiKeyValidationInfoDTO.isAuthorized());
        }
        return apiKeyValidationInfoDTO;
    }

    /**
     * Generate backend JWT for WS API requests.
     *
     * @param jwtValidationInfo       JWTValidationInfo
     * @param apiKeyValidationInfoDTO APIKeyValidationInfoDTO
     * @param apiContext              API Context
     * @param apiVersion              API Version
     * @param tokenSignature          Token signature
     * @return Backend JWT String
     * @throws APISecurityException if an error ocurrs
     */
    private String generateBackendJWTForWS(JWTValidationInfo jwtValidationInfo,
                                           APIKeyValidationInfoDTO apiKeyValidationInfoDTO,
                                           String apiContext, String apiVersion, String tokenSignature)
            throws APISecurityException {

        String endUserToken = null;
        JWTInfoDto jwtInfoDto;
        if (jwtGenerationEnabled) {
            jwtInfoDto = GatewayUtils.generateJWTInfoDto(jwtValidationInfo,
                    apiKeyValidationInfoDTO, apiContext, apiVersion);
            endUserToken = generateAndRetrieveJWTToken(tokenSignature, jwtInfoDto);
        }
        return endUserToken;
    }

    /**
     * Generate Authentication Context for WS API requests.
     *
     * @param jti                     JTI
     * @param jwtValidationInfo       JWTValidationInfo
     * @param apiKeyValidationInfoDTO APIKeyValidationInfoDTO
     * @param endUserToken            Enduser token
     * @param apiVersion              API Version
     * @return AuthenticationContext
     */
    private AuthenticationContext generateAuthenticationContextForWS(String jti, JWTValidationInfo jwtValidationInfo,
                                                                     APIKeyValidationInfoDTO apiKeyValidationInfoDTO,
                                                                     String endUserToken, String apiVersion) {
        AuthenticationContext context = GatewayUtils
                .generateAuthenticationContext(jti, jwtValidationInfo, apiKeyValidationInfoDTO,
                        endUserToken, true);
        context.setApiVersion(apiVersion);
        return context;
    }

    /**
     * Authenticates the given WebSocket handshake request with a JWT token to see if an API consumer is allowed to
     * access a particular API or not.
     *
     * @param signedJWTInfo    The JWT token sent with the API request
     * @param apiContext       The context of the invoked API
     * @param apiVersion       The version of the invoked API
     * @param matchingResource template of matching api resource
     * @return an AuthenticationContext object which contains the authentication information
     * @throws APISecurityException in case of authentication failure
     */
    @MethodStats
    public AuthenticationContext authenticateForWebSocket(SignedJWTInfo signedJWTInfo, String apiContext,
                                                          String apiVersion, String matchingResource,
                                                          boolean validateScopes)
            throws APISecurityException {

        String tokenSignature = signedJWTInfo.getSignedJWT().getSignature().toString();
        JWTClaimsSet jwtClaimsSet = signedJWTInfo.getJwtClaimsSet();
        String jti = jwtClaimsSet.getJWTID();
        JWTValidationInfo jwtValidationInfo = validateTokenForWS(signedJWTInfo, jti);

        if (jwtValidationInfo != null && jwtValidationInfo.isValid()) {
            APIKeyValidationInfoDTO apiKeyValidationInfoDTO = validateSubscriptionsForWS(jwtValidationInfo, apiContext,
                    apiVersion);
            if (apiKeyValidationInfoDTO.isAuthorized()) {
                if (validateScopes) {
                    validateScopes(apiContext, apiVersion, matchingResource,
                            WebSocketApiConstants.WEBSOCKET_DUMMY_HTTP_METHOD_NAME, jwtValidationInfo,
                            signedJWTInfo, null, false);
                }
                log.debug("JWT authentication successful. user: " + apiKeyValidationInfoDTO.getEndUserName());
                String endUserToken = generateBackendJWTForWS(jwtValidationInfo, apiKeyValidationInfoDTO, apiContext,
                        apiVersion, tokenSignature);
                return generateAuthenticationContextForWS(jti, jwtValidationInfo, apiKeyValidationInfoDTO, endUserToken,
                        apiVersion);
            } else {
                String message = "User is NOT authorized to access the Resource. API Subscription validation failed.";
                log.debug(message);
                throw new APISecurityException(apiKeyValidationInfoDTO.getValidationStatus(), message);
            }
        } else if (!jwtValidationInfo.isValid()) {
            throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS,
                    "Invalid JWT token");
        }
        throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE);
    }

    /**
     * Validate scopes bound to the resource of the API being invoked against the scopes specified
     * in the JWT token payload.
     *
     * @param apiContext               API Context
     * @param apiVersion               API Version
     * @param matchingResource         Accessed API resource
     * @param httpMethod               API resource's HTTP method
     * @param jwtValidationInfo        Validated JWT Information
     * @param jwtToken                 JWT Token
     * @param synCtx                   MessageContext
     * @param includeTokenInfoInMsgCtx Whether to include token info in message context
     * @throws APISecurityException in case of scope validation failure
     */
    private void validateScopes(String apiContext, String apiVersion, String matchingResource, String httpMethod,
                                JWTValidationInfo jwtValidationInfo, SignedJWTInfo jwtToken, MessageContext synCtx,
                                boolean includeTokenInfoInMsgCtx)
            throws APISecurityException {

        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();

        // Generate TokenValidationContext
        TokenValidationContext tokenValidationContext = new TokenValidationContext();

        APIKeyValidationInfoDTO apiKeyValidationInfoDTO = new APIKeyValidationInfoDTO();
        Set<String> scopeSet = new HashSet<>();
        scopeSet.addAll(jwtValidationInfo.getScopes());
        apiKeyValidationInfoDTO.setScopes(scopeSet);
        tokenValidationContext.setValidationInfoDTO(apiKeyValidationInfoDTO);

        tokenValidationContext.setAccessToken(jwtToken.getToken());
        tokenValidationContext.setHttpVerb(httpMethod);
        tokenValidationContext.setMatchingResource(matchingResource);
        tokenValidationContext.setContext(apiContext);
        tokenValidationContext.setVersion(apiVersion);

        boolean valid = this.apiKeyValidator.validateScopes(tokenValidationContext, tenantDomain);
        if (valid) {
            if (log.isDebugEnabled()) {
                log.debug("Scope validation successful for the resource: " + matchingResource
                        + ", user: " + jwtValidationInfo.getUser());
            }
        } else {
            String message = "User is NOT authorized to access the Resource: " + matchingResource
                    + ". Scope validation failed.";
            log.debug(message);
            if (includeTokenInfoInMsgCtx) {
                synCtx.setProperty(APIMgtGatewayConstants.ACCESS_TOKEN_INVALID_REASON, "Access token invalid");
            }
            throw new APISecurityException(APISecurityConstants.INVALID_SCOPE, message);
        }
    }

    /**
     * Validate scopes for GraphQL subscription API calls using token scopes in authentication context.
     *
     * @param apiContext            API Context
     * @param apiVersion            API Version
     * @param matchingResource      Matching resource
     * @param jwtToken              JWT Token
     * @param authenticationContext AuthenticationContext
     * @throws APISecurityException if an error occurs
     */
    public void validateScopesForGraphQLSubscriptions(String apiContext, String apiVersion, String matchingResource,
                                                      SignedJWTInfo jwtToken,
                                                      AuthenticationContext authenticationContext)
            throws APISecurityException {

        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        // Generate TokenValidationContext
        TokenValidationContext tokenValidationContext = new TokenValidationContext();

        APIKeyValidationInfoDTO apiKeyValidationInfoDTO = new APIKeyValidationInfoDTO();
        Set<String> scopeSet = new HashSet<>();
        scopeSet.addAll(authenticationContext.getRequestTokenScopes());
        apiKeyValidationInfoDTO.setScopes(scopeSet);
        tokenValidationContext.setValidationInfoDTO(apiKeyValidationInfoDTO);

        tokenValidationContext.setAccessToken(jwtToken.getToken());
        tokenValidationContext.setHttpVerb(GraphQLConstants.SubscriptionConstants.HTTP_METHOD_NAME);
        tokenValidationContext.setMatchingResource(matchingResource);
        tokenValidationContext.setContext(apiContext);
        tokenValidationContext.setVersion(apiVersion);

        boolean valid = this.apiKeyValidator.validateScopes(tokenValidationContext, tenantDomain);
        if (valid) {
            if (log.isDebugEnabled()) {
                log.debug("Scope validation successful for the resource: " + matchingResource
                        + ", user: " + authenticationContext.getUsername());
            }
        } else {
            String message = "User is NOT authorized to access the Resource: " + matchingResource
                    + ". Scope validation failed.";
            log.debug(message);
            throw new APISecurityException(APISecurityConstants.INVALID_SCOPE, message);
        }

    }

    /**
     * Check whether the jwt token is expired or not.
     *
     * @param tokenIdentifier The token Identifier of JWT.
     * @param payload        The payload of the JWT token
     * @param tenantDomain   The tenant domain from which the token cache is retrieved
     * @throws APISecurityException if the token is expired
     * @return
     */
    private JWTValidationInfo checkTokenExpiration(String tokenIdentifier, JWTValidationInfo payload,
                                                   String tenantDomain)
            throws APISecurityException {

        long timestampSkew = getTimeStampSkewInSeconds();

        Date now = new Date();
        Date exp = new Date(payload.getExpiryTime());
        if (!DateUtils.isAfter(exp, now, timestampSkew)) {
            if (isGatewayTokenCacheEnabled) {
                getGatewayTokenCache().remove(tokenIdentifier);
                getGatewayJWTTokenCache().remove(tokenIdentifier);
                getInvalidTokenCache().put(tokenIdentifier, tenantDomain);
            }
            payload.setValid(false);
            payload.setValidationCode(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS);
            payload.setExpired(true);
            return payload;
        }
        return payload;
    }

    private boolean isValidCertificateBoundAccessToken(SignedJWTInfo signedJWTInfo) throws ParseException { //Holder of Key token

        if (signedJWTInfo.getClientCertificate() == null ||
                StringUtils.isEmpty(signedJWTInfo.getClientCertificateHash()) ||
                signedJWTInfo.getCertificateThumbprint() == null) {
            return true; // If cnf is not available - 200 success
        }
        return signedJWTInfo.getClientCertificateHash().equals(signedJWTInfo.getCertificateThumbprint());
    }

    protected long getTimeStampSkewInSeconds() {

        return OAuthServerConfiguration.getInstance().getTimeStampSkewInSeconds();
    }

    private JWTValidationInfo getJwtValidationInfo(SignedJWTInfo signedJWTInfo, String jti)
            throws APISecurityException {

        String jwtHeader = signedJWTInfo.getSignedJWT().getHeader().toString();
        String tenantDomain = GatewayUtils.getTenantDomain();
        JWTValidationInfo jwtValidationInfo = null;
        if (isGatewayTokenCacheEnabled &&
                !SignedJWTInfo.ValidationStatus.NOT_VALIDATED.equals(signedJWTInfo.getValidationStatus())) {
            String cacheToken = (String) getGatewayTokenCache().get(jti);
            if (SignedJWTInfo.ValidationStatus.VALID.equals(signedJWTInfo.getValidationStatus())
                    && cacheToken != null) {
                if (getGatewayKeyCache().get(jti) != null) {
                    JWTValidationInfo tempJWTValidationInfo = (JWTValidationInfo) getGatewayKeyCache().get(jti);
                    checkTokenExpiration(jti, tempJWTValidationInfo, tenantDomain);
                                        /* Only when cnf validation fails the validation info is updated when it passes the other
                     validations are performed */
                    try {
                        if (!isValidCertificateBoundAccessToken(signedJWTInfo)) {
                            tempJWTValidationInfo.setValid(false);
                            tempJWTValidationInfo.setValidationCode(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS);
                        }
                    } catch (ParseException e) {
                        log.error("Error while parsing the certificate thumbprint", e);
                        throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                                APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE, e);
                    }
                    jwtValidationInfo = tempJWTValidationInfo;
                }
            } else if (getInvalidTokenCache().get(jti) != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Token retrieved from the invalid token cache. Token: " + GatewayUtils
                            .getMaskedToken(jwtHeader));
                }
                log.error("Invalid JWT token. " + GatewayUtils.getMaskedToken(jwtHeader));

                jwtValidationInfo = new JWTValidationInfo();
                jwtValidationInfo.setValidationCode(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS);
                jwtValidationInfo.setValid(false);
            }
        }
        if (jwtValidationInfo == null) {

            try {
                jwtValidationInfo = jwtValidationService.validateJWTToken(signedJWTInfo);
                signedJWTInfo.setValidationStatus(jwtValidationInfo.isValid() ?
                        SignedJWTInfo.ValidationStatus.VALID : SignedJWTInfo.ValidationStatus.INVALID);

                if (isGatewayTokenCacheEnabled) {
                    // Add token to tenant token cache
                    if (jwtValidationInfo.isValid()) {
                        getGatewayTokenCache().put(jti, tenantDomain);
                        getGatewayKeyCache().put(jti, jwtValidationInfo);
                    } else {
                        getInvalidTokenCache().put(jti, tenantDomain);
                    }

                    if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                        //Add the tenant domain as a reference to the super tenant cache so we know from which tenant
                        // cache
                        //to remove the entry when the need occurs to clear this particular cache entry.
                        try {
                            // Start super tenant flow
                            PrivilegedCarbonContext.startTenantFlow();
                            PrivilegedCarbonContext.getThreadLocalCarbonContext()
                                    .setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);
                            // Add token to super tenant token cache
                            if (jwtValidationInfo.isValid()) {
                                getGatewayTokenCache().put(jti, tenantDomain);
                            } else {
                                getInvalidTokenCache().put(jti, tenantDomain);
                            }
                        } finally {
                            PrivilegedCarbonContext.endTenantFlow();
                        }

                    }
                }
                return jwtValidationInfo;
            } catch (APIManagementException e) {
                throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                        APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE);
            }
        }
        return jwtValidationInfo;
    }

    private String getJWTTokenIdentifier(SignedJWTInfo signedJWTInfo) {

        JWTClaimsSet jwtClaimsSet = signedJWTInfo.getJwtClaimsSet();
        String jwtid = jwtClaimsSet.getJWTID();
        if (StringUtils.isNotEmpty(jwtid)) {
            return jwtid;
        }
        return signedJWTInfo.getSignedJWT().getSignature().toString();
    }
    protected Cache getGatewayTokenCache() {

        return CacheProvider.getGatewayTokenCache();
    }

    protected Cache getInvalidTokenCache() {

        return CacheProvider.getInvalidTokenCache();
    }

    protected Cache getGatewayKeyCache() {

        return CacheProvider.getGatewayKeyCache();
    }

    protected Cache getGatewayJWTTokenCache() {

        return CacheProvider.getGatewayJWTTokenCache();
    }
    private Map<String, String> getUserClaimsFromKeyManager(JWTInfoDto jwtInfoDto) {

        if (jwtConfigurationDto.isEnableUserClaimRetrievalFromUserStore()) {
            String tenantDomain = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();
            JWTValidationInfo jwtValidationInfo = jwtInfoDto.getJwtValidationInfo();
            if (jwtValidationInfo != null) {
                KeyManager keyManagerInstance = KeyManagerHolder.getKeyManagerInstance(tenantDomain,
                        jwtValidationInfo.getKeyManager());
                if (keyManagerInstance != null) {
                    Map<String, Object> properties = new HashMap<>();
                    if (jwtValidationInfo.getRawPayload() != null) {
                        properties.put(APIConstants.KeyManager.ACCESS_TOKEN, jwtValidationInfo.getRawPayload());
                    }
                    if (!StringUtils.isEmpty(jwtConfigurationDto.getConsumerDialectUri())) {
                        properties.put(APIConstants.KeyManager.CLAIM_DIALECT,
                                jwtConfigurationDto.getConsumerDialectUri());
                    }
                    properties.put(APIConstants.KeyManager.BINDING_FEDERATED_USER_CLAIMS,
                            jwtConfigurationDto.isBindFederatedUserClaims());
                    try {
                        return keyManagerInstance.getUserClaims(jwtInfoDto.getEndUser(), properties);
                    } catch (APIManagementException e) {
                        log.error("Error while retrieving User claims from Key Manager ", e);
                    }
                }
            }
        }

        return new HashMap<>();
    }

    /**
     * Check whether CNF claim validation is disabled or not.
     *
     * @param disableCNFValidation The Boolean property set in MessageContext for CNF claim validation
     * @param defaultVal   The default value (false: normally validation should be performed.)
     * @return a Boolean value depicting whether to perform CNF validation
     */
    private boolean isCNFValidationDisabled(Boolean disableCNFValidation, boolean defaultVal) {
        return JavaUtils.isTrueExplicitly(disableCNFValidation, defaultVal);
    }
}

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.trino.gateway.ha.config.FormAuthConfiguration;
import io.trino.gateway.ha.config.SelfSignKeyPairConfiguration;
import io.trino.gateway.ha.config.UserConfiguration;
import io.trino.gateway.ha.domain.Result;
import io.trino.gateway.ha.domain.request.RestLoginRequest;
import io.trino.gateway.ha.security.util.AuthenticationException;
import io.trino.gateway.ha.security.util.BasicCredentials;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static io.trino.gateway.ha.security.SessionCookie.OAUTH_ID_TOKEN;
import static io.trino.gateway.ha.security.SessionCookie.logOut;
import static java.util.Collections.unmodifiableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(Lifecycle.PER_CLASS)
final class TestLbAuthenticator
{
    private static final Logger log = Logger.get(TestLbAuthenticator.class);

    private static final String USER = "username";
    private static final Optional<String> MEMBER_OF = Optional.of("PVFX_DATA_31");
    private static final String ID_TOKEN = "TOKEN";

    @Test
    void testAuthenticatorGetsPrincipal()
            throws Exception
    {
        Claim claim = Mockito.mock(Claim.class);
        Mockito
                .when(claim.toString())
                .thenReturn(USER);
        AuthorizationManager authorization = Mockito.mock(AuthorizationManager.class);

        Mockito
                .when(authorization.getPrivileges(USER))
                .thenReturn(MEMBER_OF);
        LbOAuthManager authentication = Mockito.mock(LbOAuthManager.class);

        Mockito
                .when(authentication.getClaimsFromIdToken(ID_TOKEN))
                .thenReturn(Optional.of(Map.of("sub", claim)));

        Mockito
                .when(authentication.getUserIdField())
                .thenReturn("sub");

        LbPrincipal principal = new LbPrincipal(USER, MEMBER_OF);

        LbAuthenticator lbAuth = new LbAuthenticator(authentication, authorization);

        assertThat(lbAuth.authenticate(ID_TOKEN)).hasValue(principal);
    }

    @Test
    void testAuthorizationListFromOAuthField()
            throws AuthenticationException
    {
        String privilegesField = "role_list";
        Claim subClaim = Mockito.mock(Claim.class);
        Mockito.when(subClaim.asString()).thenReturn(USER);
        Claim privilegesClaim = Mockito.mock(Claim.class);
        Mockito.when(privilegesClaim.asList(String.class)).thenReturn(ImmutableList.of("admin", "api", "user"));

        LbOAuthManager oAuthManager = Mockito.mock(LbOAuthManager.class);
        Mockito
                .when(oAuthManager.getClaimsFromIdToken(ID_TOKEN))
                .thenReturn(Optional.of(Map.of("sub", subClaim, privilegesField, privilegesClaim)));
        Mockito
                .when(oAuthManager.getUserIdField())
                .thenReturn("sub");
        Mockito
                .when(oAuthManager.getPrivilegesField())
                .thenReturn(Optional.of(privilegesField));

        LbAuthenticator lbAuthenticator = new LbAuthenticator(oAuthManager, Mockito.mock(AuthorizationManager.class));
        Optional<LbPrincipal> principal = lbAuthenticator.authenticate(ID_TOKEN);
        assertThat(principal).hasValue(new LbPrincipal(USER, Optional.of("admin_api_user")));
    }

    @Test
    void testAuthorizationFieldFromOAuthField()
            throws AuthenticationException
    {
        String privilegesField = "role_list";
        Claim subClaim = Mockito.mock(Claim.class);
        Mockito.when(subClaim.asString()).thenReturn(USER);
        Claim privilegesClaim = Mockito.mock(Claim.class);
        Mockito.when(privilegesClaim.asList(String.class)).thenReturn(null);
        Mockito.when(privilegesClaim.asString()).thenReturn("admin_api");

        LbOAuthManager oAuthManager = Mockito.mock(LbOAuthManager.class);
        Mockito
                .when(oAuthManager.getClaimsFromIdToken(ID_TOKEN))
                .thenReturn(Optional.of(Map.of("sub", subClaim, privilegesField, privilegesClaim)));
        Mockito
                .when(oAuthManager.getUserIdField())
                .thenReturn("sub");
        Mockito
                .when(oAuthManager.getPrivilegesField())
                .thenReturn(Optional.of(privilegesField));

        LbAuthenticator lbAuthenticator = new LbAuthenticator(oAuthManager, Mockito.mock(AuthorizationManager.class));
        Optional<LbPrincipal> principal = lbAuthenticator.authenticate(ID_TOKEN);
        assertThat(principal).hasValue(new LbPrincipal(USER, Optional.of("admin_api")));
    }

    @Test
    void testAuthorizationFieldNotExist()
    {
        String privilegesField = "role_list";
        Claim subClaim = Mockito.mock(Claim.class);
        Mockito.when(subClaim.asString()).thenReturn(USER);

        LbOAuthManager oAuthManager = Mockito.mock(LbOAuthManager.class);
        Mockito
                .when(oAuthManager.getClaimsFromIdToken(ID_TOKEN))
                .thenReturn(Optional.of(Map.of("sub", subClaim)));
        Mockito
                .when(oAuthManager.getUserIdField())
                .thenReturn("sub");
        Mockito
                .when(oAuthManager.getPrivilegesField())
                .thenReturn(Optional.of(privilegesField));

        LbAuthenticator lbAuthenticator = new LbAuthenticator(oAuthManager, Mockito.mock(AuthorizationManager.class));
        assertThatThrownBy(() -> lbAuthenticator.authenticate(ID_TOKEN))
                .hasMessageStartingWith("Privileges field does not exist");
    }

    @Test
    void testAuthenticatorMissingClaim()
            throws Exception
    {
        Claim claim = Mockito.mock(Claim.class);
        AuthorizationManager authorization = Mockito.mock(AuthorizationManager.class);
        LbOAuthManager authentication = Mockito.mock(LbOAuthManager.class);
        Mockito
                .when(authentication.getClaimsFromIdToken(ID_TOKEN))
                .thenReturn(Optional.of(Map.of("no-sub", claim)));
        Mockito
                .when(authentication.getUserIdField())
                .thenReturn("sub");

        LbAuthenticator lbAuth = new LbAuthenticator(authentication, authorization);

        assertThat(lbAuth.authenticate(ID_TOKEN)).isEmpty();
    }

    @Test
    void testAuthenticatorUserIdFieldNotExist()
    {
        Claim claim = Mockito.mock(Claim.class);
        AuthorizationManager authorization = Mockito.mock(AuthorizationManager.class);
        LbOAuthManager authentication = Mockito.mock(LbOAuthManager.class);

        Mockito.when(authentication.getClaimsFromIdToken(ID_TOKEN))
                .thenReturn(Optional.of(Map.of("no-sub", claim)));
        Mockito.when(authentication.getUserIdField())
                .thenReturn("sub");
        Mockito.when(authentication.getPrivilegesField())
                .thenReturn(Optional.of("role_list"));

        LbAuthenticator lbAuth = new LbAuthenticator(authentication, authorization);

        assertThatThrownBy(() -> lbAuth.authenticate(ID_TOKEN))
                .hasMessageStartingWith("UserId field does not exist");
    }

    @Test
    void testPresetUsers()
            throws Exception
    {
        Map<String, UserConfiguration> presetUsers = ImmutableMap.of(
                "user1", new UserConfiguration("priv1, priv2", "pass1"),
                "user2", new UserConfiguration("priv2, priv2", "pass2"));

        LbFormAuthManager authentication = new LbFormAuthManager(null, presetUsers, new HashMap<>());

        assertThat(authentication.authenticate(new BasicCredentials("user1", "pass1")))
                .isTrue();
        assertThat(authentication.authenticate(new BasicCredentials("user2", "pass1")))
                .isFalse();
        assertThat(authentication.authenticate(new BasicCredentials("not-in-map-user", "pass1")))
                .isFalse();
    }

    @Test
    void testNoLdapNoPresetUsers()
            throws Exception
    {
        LbFormAuthManager authentication = new LbFormAuthManager(null, null, ImmutableMap.of());
        assertThat(authentication.authenticate(new BasicCredentials("user1", "pass1")))
                .isFalse();
    }

    @Test
    void testWrongLdapConfig()
            throws Exception
    {
        LbFormAuthManager authentication = new LbFormAuthManager(null, null, ImmutableMap.of());
        assertThat(authentication.authenticate(new BasicCredentials("user1", "pass1")))
                .isFalse();
    }

    @Test
    void testNullInPagePermission()
    {
        Map<String, UserConfiguration> presetUsers = ImmutableMap.of("user1", new UserConfiguration("admin, user, api", "pass1"));
        Map<String, String> pagePermission = new HashMap<>();
        pagePermission.put("user", null);

        LbFormAuthManager authentication = new LbFormAuthManager(null, presetUsers, unmodifiableMap(pagePermission));
        assertThat(authentication.authenticate(new BasicCredentials("user1", "pass1")))
                .isTrue();
    }

    @Test
    void testLogout()
            throws Exception
    {
        Response response = logOut();
        NewCookie cookie = response.getCookies().get(OAUTH_ID_TOKEN);
        log.info("value %s", cookie.getValue());
        assertThat(cookie.getValue()).isEqualTo("logout");
    }

    @Test
    void testLoginForm()
            throws Exception
    {
        SelfSignKeyPairConfiguration keyPair = new SelfSignKeyPairConfiguration(
                "src/test/resources/auth/test_private_key.pem",
                "src/test/resources/auth/test_public_key.pem");

        FormAuthConfiguration formAuthConfig = new FormAuthConfiguration();
        formAuthConfig.setSelfSignKeyPair(keyPair);

        Map<String, UserConfiguration> presetUsers = ImmutableMap.of(
                "user1", new UserConfiguration("priv1, priv2", "pass1"),
                "user2", new UserConfiguration("priv2, priv2", "pass2"));

        LbFormAuthManager lbFormAuthManager = new LbFormAuthManager(formAuthConfig, presetUsers, new HashMap<>());
        RestLoginRequest restLoginRequest = new RestLoginRequest("user1", "pass1");
        Result<?> r = lbFormAuthManager.processRESTLogin(restLoginRequest);
        assertThat(Result.isSuccess(r)).isTrue();
        Map data = (Map) r.getData();
        String token = (String) data.get("token");
        log.info(token);
        JWT.decode(token);
    }
}

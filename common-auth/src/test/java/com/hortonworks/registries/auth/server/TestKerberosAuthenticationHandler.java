/**
 * Copyright 2016-2021 Cloudera, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package com.hortonworks.registries.auth.server;

import org.apache.hadoop.minikdc.KerberosSecurityTestcase;
import com.hortonworks.registries.auth.KerberosTestUtils;
import com.hortonworks.registries.auth.client.AuthenticationException;
import com.hortonworks.registries.auth.client.KerberosAuthenticator;
import org.apache.commons.codec.binary.Base64;
import com.hortonworks.registries.auth.util.KerberosName;
import com.hortonworks.registries.auth.util.KerberosUtil;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.ietf.jgss.Oid;

import javax.security.auth.kerberos.KerberosPrincipal;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

@Ignore
public class TestKerberosAuthenticationHandler
        extends KerberosSecurityTestcase {

    protected KerberosAuthenticationHandler handler;

    protected Properties kerberosHandlerProps = null;

    protected KerberosAuthenticationHandler getNewAuthenticationHandler() {
        return new KerberosAuthenticationHandler();
    }

    protected String getExpectedType() {
        return KerberosAuthenticationHandler.TYPE;
    }

    protected Properties getDefaultProperties() {
        Properties props = new Properties();
        props.setProperty(KerberosAuthenticationHandler.PRINCIPAL,
                KerberosTestUtils.getServerPrincipal());
        props.setProperty(KerberosAuthenticationHandler.KEYTAB,
                KerberosTestUtils.getKeytabFile());
        props.setProperty(KerberosAuthenticationHandler.NAME_RULES,
                "RULE:[1:$1@$0](.*@" + KerberosTestUtils.getRealm() + ")s/@.*//\n");
        return props;
    }

    @Before
    public void setup() throws Exception {
        Properties props = loadProperties();
        try {
            handler.init(props);
        } catch (Exception ex) {
            handler = null;
            throw ex;
        }
    }

    Properties loadProperties() throws Exception {
        if (kerberosHandlerProps != null) {
            return kerberosHandlerProps;
        }

        kerberosHandlerProps = getDefaultProperties();
        // create keytab
        File keytabFile = new File(KerberosTestUtils.getKeytabFile());
        String clientPrincipal = KerberosTestUtils.getClientPrincipal();
        String serverPrincipal = KerberosTestUtils.getServerPrincipal();
        clientPrincipal = clientPrincipal.substring(0, clientPrincipal.lastIndexOf("@"));
        serverPrincipal = serverPrincipal.substring(0, serverPrincipal.lastIndexOf("@"));
        getKdc().createPrincipal(keytabFile, clientPrincipal, serverPrincipal);
        // handler
        handler = getNewAuthenticationHandler();
        return kerberosHandlerProps;
    }

    @Test(timeout = 60000)
    public void testNameRules() throws Exception {
        KerberosName kn = new KerberosName(KerberosTestUtils.getServerPrincipal());
        Assert.assertEquals(KerberosTestUtils.getRealm(), kn.getRealm());

        //destroy handler created in setUp()
        handler.destroy();

        KerberosName.setRules("RULE:[1:$1@$0](.*@FOO)s/@.*//\nDEFAULT");

        handler = getNewAuthenticationHandler();
        Properties props = getDefaultProperties();
        props.setProperty(KerberosAuthenticationHandler.NAME_RULES, "RULE:[1:$1@$0](.*@BAR)s/@.*//\nDEFAULT");
        try {
            handler.init(props);
        } catch (Exception ex) {
        }
        kn = new KerberosName("bar@BAR");
        Assert.assertEquals("bar", kn.getShortName());
        kn = new KerberosName("bar@FOO");
        Assert.assertEquals("bar@FOO", kn.getShortName());
    }

    @Test(timeout = 60000)
    public void testInit() throws Exception {
        Assert.assertEquals(KerberosTestUtils.getKeytabFile(), handler.getKeytab());
        Set<KerberosPrincipal> principals = handler.getPrincipals();
        Principal expectedPrincipal =
                new KerberosPrincipal(KerberosTestUtils.getServerPrincipal());
        Assert.assertTrue(principals.contains(expectedPrincipal));
        Assert.assertEquals(1, principals.size());
    }

    // dynamic configuration of HTTP principals
    @Test(timeout = 60000)
    public void testDynamicPrincipalDiscovery() throws Exception {
        String[] keytabUsers = new String[]{
                "HTTP/host1", "HTTP/host2", "HTTP2/host1", "XHTTP/host"
        };
        String keytab = KerberosTestUtils.getKeytabFile();
        getKdc().createPrincipal(new File(keytab), keytabUsers);

        // destroy handler created in setUp()
        handler.destroy();
        Properties props = new Properties();
        props.setProperty(KerberosAuthenticationHandler.KEYTAB, keytab);
        props.setProperty(KerberosAuthenticationHandler.PRINCIPAL, "*");
        handler = getNewAuthenticationHandler();
        handler.init(props);

        Assert.assertEquals(KerberosTestUtils.getKeytabFile(), handler.getKeytab());

        Set<KerberosPrincipal> loginPrincipals = handler.getPrincipals();
        for (String user : keytabUsers) {
            Principal principal = new KerberosPrincipal(
                    user + "@" + KerberosTestUtils.getRealm());
            boolean expected = user.startsWith("HTTP/");
            Assert.assertEquals("checking for " + user, expected,
                    loginPrincipals.contains(principal));
        }
    }

    // dynamic configuration of HTTP principals
    @Test(timeout = 60000)
    public void testDynamicPrincipalDiscoveryMissingPrincipals() throws Exception {
        String[] keytabUsers = new String[]{"hdfs/localhost"};
        String keytab = KerberosTestUtils.getKeytabFile();
        getKdc().createPrincipal(new File(keytab), keytabUsers);

        // destroy handler created in setUp()
        handler.destroy();
        Properties props = new Properties();
        props.setProperty(KerberosAuthenticationHandler.KEYTAB, keytab);
        props.setProperty(KerberosAuthenticationHandler.PRINCIPAL, "*");
        handler = getNewAuthenticationHandler();
        try {
            handler.init(props);
            Assert.fail("init should have failed");
        } catch (ServletException ex) {
            Assert.assertEquals("Principals do not exist in the keytab",
                    ex.getCause().getMessage());
        } catch (Throwable t) {
            Assert.fail("wrong exception: " + t);
        }
    }

    @Test(timeout = 60000)
    public void testType() throws Exception {
        Assert.assertEquals(getExpectedType(), handler.getType());
    }

    @Test
    public void testTrustedProxyNilConfig() {
        KerberosAuthenticationHandler handler = new KerberosAuthenticationHandler();
        KerberosAuthenticationHandler.ProxyUserAuthorization proxyUserAuthorization = handler.new ProxyUserAuthorization(new Properties());
        Assert.assertFalse(proxyUserAuthorization.authorize("knox", "127.0.0.1"));
    }

    @Test
    public void testTrustedProxyConfig() {
        KerberosAuthenticationHandler handler = new KerberosAuthenticationHandler();
        KerberosAuthenticationHandler.ProxyUserAuthorization proxyUserAuthorization = handler.new ProxyUserAuthorization(new Properties() {{
            put("proxyuser.knox.hosts", "127.0.0.1");
            put("proxyuser.admin.hosts", "10.222.0.0,10.113.221.221");
            put("proxyuser.user1.hosts", "*");
        }});
        Assert.assertTrue(proxyUserAuthorization.authorize("knox", "127.0.0.1"));
        Assert.assertFalse(proxyUserAuthorization.authorize("knox", "10.222.0.0"));

        Assert.assertTrue(proxyUserAuthorization.authorize("admin", "10.222.0.0"));
        Assert.assertTrue(proxyUserAuthorization.authorize("admin", "10.113.221.221"));
        Assert.assertFalse(proxyUserAuthorization.authorize("admin", "127.0.0.1"));

        Assert.assertTrue(proxyUserAuthorization.authorize("user1", "10.222.0.0"));
        Assert.assertTrue(proxyUserAuthorization.authorize("user1", "10.113.221.221"));
        Assert.assertTrue(proxyUserAuthorization.authorize("user1", "127.0.0.1"));
    }

    @Test
    public void testProxyDoAsUser() throws Exception {
        Properties props = loadProperties();
        props.setProperty(KerberosAuthenticationHandler.ENABLE_TRUSTED_PROXY, Boolean.TRUE.toString());
        props.setProperty("proxyuser.client.hosts", "10.222.0.0");

        KerberosAuthenticationHandler kerberosAuthHandler = new KerberosAuthenticationHandler();
        kerberosAuthHandler.init(props);

        //Request comes from proxyuser from whitelisted host
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        String token = generateClientToken();
        Mockito.when(request.getHeader(KerberosAuthenticator.AUTHORIZATION))
                .thenReturn(KerberosAuthenticator.NEGOTIATE + " " + token);
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getRemoteAddr()).thenReturn("10.222.0.0");
        Mockito.when(request.getQueryString()).thenReturn("doAs=user1");

        AuthenticationToken authToken = kerberosAuthHandler.authenticate(request, response);
        Assert.assertEquals("user1", authToken.getName());
        Assert.assertEquals("user1", authToken.getUserName());

        //Request comes from proxyuser from non-whitelisted host with a doAsUser
        HttpServletRequest request2 = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response2 = Mockito.mock(HttpServletResponse.class);
        String token2 = generateClientToken();
        Mockito.when(request.getHeader(KerberosAuthenticator.AUTHORIZATION))
                .thenReturn(KerberosAuthenticator.NEGOTIATE + " " + token2);
        Mockito.when(request2.getServerName()).thenReturn("localhost");
        Mockito.when(request2.getRemoteAddr()).thenReturn("10.222.0.1");
        Mockito.when(request2.getQueryString()).thenReturn("doAs=user1");

        AuthenticationToken authToken2 = kerberosAuthHandler.authenticate(request2, response2);
        Assert.assertNull(authToken2);

        //Request comes from proxyuser from non-whitelisted host without a doAsUser
        HttpServletRequest request3 = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response3 = Mockito.mock(HttpServletResponse.class);
        String token3 = generateClientToken();
        Mockito.when(request3.getHeader(KerberosAuthenticator.AUTHORIZATION))
                .thenReturn(KerberosAuthenticator.NEGOTIATE + " " + token3);
        Mockito.when(request3.getServerName()).thenReturn("localhost");
        Mockito.when(request3.getRemoteAddr()).thenReturn("10.222.0.0");

        AuthenticationToken authToken3 = kerberosAuthHandler.authenticate(request3, response3);
        Assert.assertEquals("client@EXAMPLE.COM", authToken3.getName());
        Assert.assertEquals("client", authToken3.getUserName());

        kerberosAuthHandler.destroy();
    }

    @Test
    public void testGetDoAsUser() throws UnsupportedEncodingException {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getQueryString()).thenReturn("doAs=user1");
        Assert.assertEquals("user1", KerberosAuthenticationHandler.getDoasUser(request));

        Mockito.when(request.getQueryString()).thenReturn("x=y&z=a");
        Assert.assertNull(KerberosAuthenticationHandler.getDoasUser(request));

        Mockito.when(request.getQueryString()).thenReturn("x=y&doAs=");
        Assert.assertNull(KerberosAuthenticationHandler.getDoasUser(request));

        Mockito.when(request.getQueryString()).thenReturn("x=y&doAs=%20");
        Assert.assertNull(KerberosAuthenticationHandler.getDoasUser(request));
    }

    public void testRequestWithoutAuthorization() throws Exception {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Assert.assertNull(handler.authenticate(request, response));
        Mockito.verify(response).setHeader(KerberosAuthenticator.WWW_AUTHENTICATE, KerberosAuthenticator.NEGOTIATE);
        Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    public void testRequestWithInvalidAuthorization() throws Exception {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getHeader(KerberosAuthenticator.AUTHORIZATION)).thenReturn("invalid");
        Assert.assertNull(handler.authenticate(request, response));
        Mockito.verify(response).setHeader(KerberosAuthenticator.WWW_AUTHENTICATE, KerberosAuthenticator.NEGOTIATE);
        Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test(timeout = 60000)
    public void testRequestWithIncompleteAuthorization() throws Exception {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getHeader(KerberosAuthenticator.AUTHORIZATION))
                .thenReturn(KerberosAuthenticator.NEGOTIATE);
        try {
            handler.authenticate(request, response);
            Assert.fail();
        } catch (AuthenticationException ex) {
            // Expected
        } catch (Exception ex) {
            Assert.fail();
        }
    }

    public void testRequestWithAuthorization() throws Exception {
        String token = generateClientToken();

        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getHeader(KerberosAuthenticator.AUTHORIZATION))
                .thenReturn(KerberosAuthenticator.NEGOTIATE + " " + token);
        Mockito.when(request.getServerName()).thenReturn("localhost");

        AuthenticationToken authToken = handler.authenticate(request, response);

        if (authToken != null) {
            Mockito.verify(response).setHeader(Mockito.eq(KerberosAuthenticator.WWW_AUTHENTICATE),
                    Mockito.matches(KerberosAuthenticator.NEGOTIATE + " .*"));
            Mockito.verify(response).setStatus(HttpServletResponse.SC_OK);

            Assert.assertEquals(KerberosTestUtils.getClientPrincipal(), authToken.getName());
            Assert.assertTrue(KerberosTestUtils.getClientPrincipal().startsWith(authToken.getUserName()));
            Assert.assertEquals(getExpectedType(), authToken.getType());
        } else {
            Mockito.verify(response).setHeader(Mockito.eq(KerberosAuthenticator.WWW_AUTHENTICATE),
                    Mockito.matches(KerberosAuthenticator.NEGOTIATE + " .*"));
            Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    public void testRequestWithInvalidKerberosAuthorization() throws Exception {

        String token = new Base64(0).encodeToString(new byte[]{0, 1, 2});

        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getHeader(KerberosAuthenticator.AUTHORIZATION)).thenReturn(
                KerberosAuthenticator.NEGOTIATE + token);

        try {
            handler.authenticate(request, response);
            Assert.fail();
        } catch (AuthenticationException ex) {
            // Expected
        } catch (Exception ex) {
            Assert.fail();
        }
    }

    String generateClientToken() throws Exception {
        String token = KerberosTestUtils.doAsClient(new Callable<String>() {
            @Override
            public String call() throws Exception {
                GSSManager gssManager = GSSManager.getInstance();
                GSSContext gssContext = null;
                try {
                    String servicePrincipal = KerberosTestUtils.getServerPrincipal();
                    Oid oid = KerberosUtil.getOidInstance("NT_GSS_KRB5_PRINCIPAL");
                    GSSName serviceName = gssManager.createName(servicePrincipal,
                            oid);
                    oid = KerberosUtil.getOidInstance("GSS_KRB5_MECH_OID");
                    gssContext = gssManager.createContext(serviceName, oid, null,
                            GSSContext.DEFAULT_LIFETIME);
                    gssContext.requestCredDeleg(true);
                    gssContext.requestMutualAuth(true);

                    byte[] inToken = new byte[0];
                    byte[] outToken = gssContext.initSecContext(inToken, 0, inToken.length);
                    Base64 base64 = new Base64(0);
                    return base64.encodeToString(outToken);

                } finally {
                    if (gssContext != null) {
                        gssContext.dispose();
                    }
                }
            }
        });
        return token;
    }

    @After
    public void tearDown() throws Exception {
        if (handler != null) {
            handler.destroy();
            handler = null;
        }
    }
}

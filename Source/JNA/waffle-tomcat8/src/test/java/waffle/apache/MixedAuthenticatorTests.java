/**
 * Waffle (https://github.com/dblock/waffle)
 *
 * Copyright (c) 2010 - 2015 Application Security, Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Application Security, Inc.
 */
package waffle.apache;

import javax.servlet.ServletException;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Valve;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import waffle.apache.catalina.SimpleContext;
import waffle.apache.catalina.SimpleEngine;
import waffle.apache.catalina.SimpleHttpRequest;
import waffle.apache.catalina.SimpleHttpResponse;
import waffle.apache.catalina.SimplePipeline;
import waffle.apache.catalina.SimpleRealm;
import waffle.apache.catalina.SimpleServletContext;
import waffle.mock.MockWindowsAuthProvider;
import waffle.windows.auth.IWindowsCredentialsHandle;
import waffle.windows.auth.impl.WindowsAccountImpl;
import waffle.windows.auth.impl.WindowsCredentialsHandleImpl;
import waffle.windows.auth.impl.WindowsSecurityContextImpl;

import com.google.common.io.BaseEncoding;
import com.sun.jna.platform.win32.Sspi;
import com.sun.jna.platform.win32.Sspi.SecBufferDesc;

/**
 * Waffle Tomcat Mixed Authenticator Tests.
 * 
 * @author dblock[at]dblock[dot]org
 */
public class MixedAuthenticatorTests {

    private MixedAuthenticator authenticator;

    @Before
    public void setUp() throws LifecycleException {
        this.authenticator = new MixedAuthenticator();
        final LoginConfig loginConfig = new LoginConfig();
        loginConfig.setErrorPage("error.html");
        loginConfig.setLoginPage("login.html");
        final SimpleContext ctx = Mockito.mock(SimpleContext.class, Mockito.CALLS_REAL_METHODS);
        Mockito.when(ctx.getLoginConfig()).thenReturn(loginConfig);
        ctx.setServletContext(Mockito.mock(SimpleServletContext.class, Mockito.CALLS_REAL_METHODS));
        ctx.setPath("/");
        ctx.setName("SimpleContext");
        ctx.setRealm(Mockito.mock(SimpleRealm.class, Mockito.CALLS_REAL_METHODS));
        final SimpleEngine engine = Mockito.mock(SimpleEngine.class, Mockito.CALLS_REAL_METHODS);
        ctx.setParent(engine);
        final SimplePipeline pipeline = Mockito.mock(SimplePipeline.class, Mockito.CALLS_REAL_METHODS);
        pipeline.setValves(new Valve[0]);
        engine.setPipeline(pipeline);
        ctx.setPipeline(pipeline);
        ctx.setAuthenticator(this.authenticator);
        this.authenticator.setContainer(ctx);
        this.authenticator.start();
    }

    @After
    public void tearDown() throws LifecycleException {
        this.authenticator.stop();
    }

    @Test
    public void testChallengeGET() {
        final SimpleHttpRequest request = new SimpleHttpRequest();
        request.setMethod("GET");
        request.setQueryString("j_negotiate_check");
        final SimpleHttpResponse response = new SimpleHttpResponse();
        this.authenticator.authenticate(request, response);
        final String[] wwwAuthenticates = response.getHeaderValues("WWW-Authenticate");
        Assert.assertNotNull(wwwAuthenticates);
        Assert.assertEquals(2, wwwAuthenticates.length);
        Assert.assertEquals("Negotiate", wwwAuthenticates[0]);
        Assert.assertEquals("NTLM", wwwAuthenticates[1]);
        Assert.assertEquals("close", response.getHeader("Connection"));
        Assert.assertEquals(2, response.getHeaderNames().size());
        Assert.assertEquals(401, response.getStatus());
    }

    @Test
    public void testChallengePOST() {
        final String securityPackage = "Negotiate";
        IWindowsCredentialsHandle clientCredentials = null;
        WindowsSecurityContextImpl clientContext = null;
        try {
            // client credentials handle
            clientCredentials = WindowsCredentialsHandleImpl.getCurrent(securityPackage);
            clientCredentials.initialize();
            // initial client security context
            clientContext = new WindowsSecurityContextImpl();
            clientContext.setPrincipalName(WindowsAccountImpl.getCurrentUsername());
            clientContext.setCredentialsHandle(clientCredentials.getHandle());
            clientContext.setSecurityPackage(securityPackage);
            clientContext.initialize(null, null, WindowsAccountImpl.getCurrentUsername());
            final SimpleHttpRequest request = new SimpleHttpRequest();
            request.setQueryString("j_negotiate_check");
            request.setMethod("POST");
            request.setContentLength(0);
            final String clientToken = BaseEncoding.base64().encode(clientContext.getToken());
            request.addHeader("Authorization", securityPackage + " " + clientToken);
            final SimpleHttpResponse response = new SimpleHttpResponse();
            this.authenticator.authenticate(request, response);
            Assert.assertTrue(response.getHeader("WWW-Authenticate").startsWith(securityPackage + " "));
            Assert.assertEquals("keep-alive", response.getHeader("Connection"));
            Assert.assertEquals(2, response.getHeaderNames().size());
            Assert.assertEquals(401, response.getStatus());
        } finally {
            if (clientContext != null) {
                clientContext.dispose();
            }
            if (clientCredentials != null) {
                clientCredentials.dispose();
            }
        }
    }

    @Test
    public void testGet() {

        final SimpleHttpRequest request = new SimpleHttpRequest();
        final SimpleHttpResponse response = new SimpleHttpResponse();
        Assert.assertFalse(this.authenticator.authenticate(request, response));
        Assert.assertEquals(304, response.getStatus());
        Assert.assertEquals("login.html", response.getHeader("Location"));
        Assert.assertEquals(1, response.getHeaderNames().size());
    }

    @Test
    public void testGetInfo() {
        Assertions.assertThat(this.authenticator.getInfo().length()).isGreaterThan(0);
    }

    @Test
    public void testNegotiate() {
        final String securityPackage = "Negotiate";
        IWindowsCredentialsHandle clientCredentials = null;
        WindowsSecurityContextImpl clientContext = null;
        try {
            // client credentials handle
            clientCredentials = WindowsCredentialsHandleImpl.getCurrent(securityPackage);
            clientCredentials.initialize();
            // initial client security context
            clientContext = new WindowsSecurityContextImpl();
            clientContext.setPrincipalName(WindowsAccountImpl.getCurrentUsername());
            clientContext.setCredentialsHandle(clientCredentials.getHandle());
            clientContext.setSecurityPackage(securityPackage);
            clientContext.initialize(null, null, WindowsAccountImpl.getCurrentUsername());
            // negotiate
            boolean authenticated = false;
            final SimpleHttpRequest request = new SimpleHttpRequest();
            request.setQueryString("j_negotiate_check");
            String clientToken;
            while (true) {
                clientToken = BaseEncoding.base64().encode(clientContext.getToken());
                request.addHeader("Authorization", securityPackage + " " + clientToken);

                final SimpleHttpResponse response = new SimpleHttpResponse();
                authenticated = this.authenticator.authenticate(request, response);

                if (authenticated) {
                    Assertions.assertThat(response.getHeaderNames().size()).isGreaterThanOrEqualTo(0);
                    break;
                }

                Assert.assertTrue(response.getHeader("WWW-Authenticate").startsWith(securityPackage + " "));
                Assert.assertEquals("keep-alive", response.getHeader("Connection"));
                Assert.assertEquals(2, response.getHeaderNames().size());
                Assert.assertEquals(401, response.getStatus());
                final String continueToken = response.getHeader("WWW-Authenticate").substring(
                        securityPackage.length() + 1);
                final byte[] continueTokenBytes = BaseEncoding.base64().decode(continueToken);
                Assertions.assertThat(continueTokenBytes.length).isGreaterThan(0);
                final SecBufferDesc continueTokenBuffer = new SecBufferDesc(Sspi.SECBUFFER_TOKEN, continueTokenBytes);
                clientContext.initialize(clientContext.getHandle(), continueTokenBuffer,
                        WindowsAccountImpl.getCurrentUsername());
            }
            Assert.assertTrue(authenticated);
        } finally {
            if (clientContext != null) {
                clientContext.dispose();
            }
            if (clientCredentials != null) {
                clientCredentials.dispose();
            }
        }
    }

    @Test
    public void testPostSecurityCheck() {
        final SimpleHttpRequest request = new SimpleHttpRequest();
        request.setQueryString("j_security_check");
        request.addParameter("j_username", "username");
        request.addParameter("j_password", "password");
        final SimpleHttpResponse response = new SimpleHttpResponse();
        Assert.assertFalse(this.authenticator.authenticate(request, response));
        Assert.assertEquals(304, response.getStatus());
        Assert.assertEquals("error.html", response.getHeader("Location"));
        Assert.assertEquals(1, response.getHeaderNames().size());
    }

    @Test
    public void testProgrammaticSecurity() throws ServletException {
        this.authenticator.setAuth(new MockWindowsAuthProvider());
        final SimpleHttpRequest request = new SimpleHttpRequest();
        request.getMappingData().context = (Context) this.authenticator.getContainer();

        request.login(WindowsAccountImpl.getCurrentUsername(), "");

        // TODO Why is remote user null here?
        // Assert.assertEquals(WindowsAccountImpl.getCurrentUsername(), request.getRemoteUser());
        Assert.assertTrue(request.getUserPrincipal() instanceof GenericWindowsPrincipal);
        final GenericWindowsPrincipal windowsPrincipal = (GenericWindowsPrincipal) request.getUserPrincipal();
        Assert.assertTrue(windowsPrincipal.getSidString().startsWith("S-"));
    }

    @Test
    public void testSecurityCheckParameters() {
        this.authenticator.setAuth(new MockWindowsAuthProvider());
        final SimpleHttpRequest request = new SimpleHttpRequest();
        request.addParameter("j_security_check", "");
        request.addParameter("j_username", WindowsAccountImpl.getCurrentUsername());
        request.addParameter("j_password", "");
        final SimpleHttpResponse response = new SimpleHttpResponse();
        Assert.assertTrue(this.authenticator.authenticate(request, response));
    }

    @Test
    public void testSecurityCheckQueryString() {
        this.authenticator.setAuth(new MockWindowsAuthProvider());
        final SimpleHttpRequest request = new SimpleHttpRequest();
        request.setQueryString("j_security_check");
        request.addParameter("j_username", WindowsAccountImpl.getCurrentUsername());
        request.addParameter("j_password", "");
        final SimpleHttpResponse response = new SimpleHttpResponse();
        Assert.assertTrue(this.authenticator.authenticate(request, response));
    }
}

/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.modules.fetcher;

import static org.archive.modules.fetcher.FetchHTTPTestServers.BASIC_AUTH_LOGIN;
import static org.archive.modules.fetcher.FetchHTTPTestServers.BASIC_AUTH_PASSWORD;
import static org.archive.modules.fetcher.FetchHTTPTestServers.BASIC_AUTH_REALM;
import static org.archive.modules.fetcher.FetchHTTPTestServers.DEFAULT_GZIPPED_PAYLOAD;
import static org.archive.modules.fetcher.FetchHTTPTestServers.DEFAULT_PAYLOAD_STRING;
import static org.archive.modules.fetcher.FetchHTTPTestServers.DIGEST_AUTH_LOGIN;
import static org.archive.modules.fetcher.FetchHTTPTestServers.DIGEST_AUTH_PASSWORD;
import static org.archive.modules.fetcher.FetchHTTPTestServers.DIGEST_AUTH_REALM;
import static org.archive.modules.fetcher.FetchHTTPTestServers.ETAG_TEST_VALUE;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.io.IOUtils;
import org.apache.http.NoHttpResponseException;
import org.archive.httpclient.ConfigurableX509TrustManager.TrustLevel;
import org.archive.modules.CoreAttributeConstants;
import org.archive.modules.CrawlMetadata;
import org.archive.modules.CrawlURI;
import org.archive.modules.CrawlURI.FetchType;
import org.archive.modules.credential.HttpAuthenticationCredential;
import org.archive.modules.deciderules.RejectDecideRule;
import org.archive.modules.forms.HTMLForm.NameValue;
import org.archive.modules.recrawl.FetchHistoryProcessor;
import org.archive.modules.revisit.ServerNotModifiedRevisit;
import org.archive.net.UURI;
import org.archive.net.UURIFactory;
import org.archive.util.Recorder;
import org.archive.util.TmpDirTestCase;
import org.bbottema.javasocksproxyserver.SocksServer;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.*;

public class FetchHTTPTest {
    
    // private static Logger logger = Logger.getLogger(FetchHTTPTests.class.getName());

    // static {
    //     Logger.getLogger("").setLevel(Level.FINE);
    //     for (java.util.logging.Handler h : Logger.getLogger("").getHandlers()) {
    //         h.setLevel(Level.ALL);
    //         h.setFormatter(new OneLineSimpleLogger());
    //     }
    // }

    protected FetchHTTP fetcher;
    protected FetchHTTP socksFetcher;
    protected SocksServer socksServer;

    protected FetchHTTP fetcher() throws IOException {
        if (fetcher == null) { 
            fetcher = makeModule();
        }
        
        return fetcher;
    }

    protected FetchHTTP socksFetcher() throws Exception {
        if (socksFetcher == null) {
            socksFetcher = makeSocksModule();
        }

        return socksFetcher;
    }

    protected String getUserAgentString() {
        return getClass().getName();
    }

    protected void runDefaultChecks(CrawlURI curi, String... exclusionsArray)
        throws IOException, UnsupportedEncodingException {

        Set<String> exclusions = new HashSet<String>(Arrays.asList(exclusionsArray));
        
        String requestString = httpRequestString(curi);
        if (!exclusions.contains("requestLine")) {
            assertTrue(requestString.startsWith("GET / HTTP/1.0\r\n"));
        }
        assertTrue(requestString.contains("User-Agent: " + getUserAgentString() + "\r\n"));
        assertTrue(requestString.matches("(?s).*Connection: [Cc]lose\r\n.*"));
        if (!exclusions.contains("acceptHeaders")) {
            assertTrue(requestString.contains("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n"));
        }
        if (!exclusions.contains("hostHeader")) {
            assertTrue(requestString.contains("Host: localhost:7777\r\n"));
        }
        if (!exclusions.contains("trailingCRLFCRLF")) {
            assertTrue(requestString.endsWith("\r\n\r\n"));
        }
        
        // check sizes
        assertEquals(DEFAULT_PAYLOAD_STRING.length(), curi.getContentLength());
        assertEquals(curi.getContentSize(), curi.getRecordedSize());
        
        // check various
        assertNotNull(curi.getServerIP());
        assertEquals("sha1:TQ5R6YVOZLTQENRIIENVGXHOPX3YCRNJ", curi.getContentDigestSchemeString());
        if (!exclusions.contains("contentType")) {
            assertEquals("text/plain;charset=US-ASCII", curi.getContentType());
            assertEquals(Charset.forName("US-ASCII"), curi.getRecorder().getCharset());
        }
        assertTrue(curi.getCredentials().isEmpty());
        assertTrue(curi.getFetchDuration() >= 0);
        if (!exclusions.contains("fetchStatus")) {
            assertTrue(curi.getFetchStatus() == 200);
        }
        if (!exclusions.contains("fetchTypeGET")) {
            assertTrue(curi.getFetchType() == FetchType.HTTP_GET);
        }
        
        // check message body, i.e. "raw, possibly chunked-transfer-encoded message contents not including the leading headers"
        assertEquals(DEFAULT_PAYLOAD_STRING, messageBodyString(curi));

        // check entity, i.e. "message-body after any (usually-unnecessary) transfer-decoding but before any content-encoding (eg gzip) decoding"
        assertEquals(DEFAULT_PAYLOAD_STRING, entityString(curi));

        // check content, i.e. message-body after possibly tranfer-decoding and after content-encoding (eg gzip) decoding
        assertEquals(DEFAULT_PAYLOAD_STRING, contentString(curi));
        assertEquals(DEFAULT_PAYLOAD_STRING.substring(0, 10), curi.getRecorder().getContentReplayPrefixString(10));
        assertEquals(DEFAULT_PAYLOAD_STRING, curi.getRecorder().getContentReplayCharSequence().toString());
        
        if (!exclusions.contains("httpBindAddress")) {
            assertTrue(rawResponseString(curi).contains("Client-Host: 127.0.0.1\r\n"));
        }
        
        if (!exclusions.contains("nonFatalFailuresIsEmpty")) {
        	assertTrue(curi.getNonFatalFailures().isEmpty());
        }
    }

    // convenience methods to get strings from raw recorded i/o
    /**
     * Raw response including headers.
     */
    static protected String rawResponseString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    /**
     * Raw message body, before any unchunking or content-decoding.
     */
    static protected String messageBodyString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getMessageBodyReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    /**
     * Message body after unchunking but before content-decoding.
     */
    static protected String entityString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getEntityReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    /**
     * Unchunked, content-decoded message body.
     */
    static protected String contentString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getContentReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    static protected String httpRequestString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getRecordedOutput().getReplayInputStream());
        return new String(buf, "US-ASCII");
    }

    @Test
    public void testDefaults() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().process(curi);
        // logger.info('\n' + httpRequestString(curi) + rawResponseString(curi));
        runDefaultChecks(curi);
    }

    @Test
    public void testAcceptHeaders() throws Exception {
        List<String> headers = Arrays.asList("header1: value1", "header2: value2");
        fetcher().setAcceptHeaders(headers);
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().process(curi);

        runDefaultChecks(curi, "acceptHeaders");
        
        // special checks for this test
        String requestString = httpRequestString(curi);
        assertFalse(requestString.contains("Accept:"));
        for (String h: headers) {
            assertTrue(requestString.contains(h));
        }
    }

    @Test
    public void testCookies() throws Exception {
        checkSetCookieURI();
        
        // second request to see if cookie is sent
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().process(curi);
        runDefaultChecks(curi);
        
        String requestString = httpRequestString(curi);
        assertTrue(requestString.contains("Cookie: test-cookie-name=test-cookie-value\r\n"));
    }

    @Test
    public void testIgnoreCookies() throws Exception {
        fetcher().setIgnoreCookies(true);
        checkSetCookieURI();

        // second request to see if cookie is NOT sent
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().process(curi);
        runDefaultChecks(curi);

        String requestString = httpRequestString(curi);
        assertFalse(requestString.contains("Cookie:"));
    }

    @Test
    public void testBasicAuth() throws Exception {
        HttpAuthenticationCredential basicAuthCredential = new HttpAuthenticationCredential();
        basicAuthCredential.setRealm(BASIC_AUTH_REALM);
        basicAuthCredential.setDomain("localhost:7777");
        basicAuthCredential.setLogin(BASIC_AUTH_LOGIN);
        basicAuthCredential.setPassword(BASIC_AUTH_PASSWORD);
        
        fetcher().getCredentialStore().getCredentials().put("basic-auth-credential",
                basicAuthCredential);

        CrawlURI curi = makeCrawlURI("http://localhost:7777/auth/1");
        fetcher().process(curi);

        // check that we got the expected response and the fetcher did its thing
        assertEquals(401, curi.getFetchStatus());
        assertEquals("basic realm=\"basic-auth-realm\"", curi.getHttpResponseHeader("WWW-Authenticate"));
        assertTrue(curi.getCredentials().contains(basicAuthCredential));
        assertTrue(curi.getHttpAuthChallenges() != null && curi.getHttpAuthChallenges().containsKey("basic"));
        
        // fetch again with the credentials
        fetcher().process(curi);
        String httpRequestString = httpRequestString(curi);
        assertTrue(httpRequestString.contains("Authorization: Basic YmFzaWMtYXV0aC1sb2dpbjpiYXNpYy1hdXRoLXBhc3N3b3Jk\r\n"));
        // otherwise should be a normal 200 response
        runDefaultChecks(curi, "requestLine");
        
        // fetch a fresh uri to make sure auth info was cached and we don't get another 401
        curi = makeCrawlURI("http://localhost:7777/auth/2");
        fetcher().process(curi);
        httpRequestString = httpRequestString(curi);
        assertTrue(httpRequestString.contains("Authorization: Basic YmFzaWMtYXV0aC1sb2dpbjpiYXNpYy1hdXRoLXBhc3N3b3Jk\r\n"));
        // otherwise should be a normal 200 response
        runDefaultChecks(curi, "requestLine");
    }

    // server for digest auth is at localhost:7778
    @Test
    public void testDigestAuth() throws Exception {
        HttpAuthenticationCredential digestAuthCred = new HttpAuthenticationCredential();
        digestAuthCred.setRealm(DIGEST_AUTH_REALM);
        digestAuthCred.setDomain("localhost:7778");
        digestAuthCred.setLogin(DIGEST_AUTH_LOGIN);
        digestAuthCred.setPassword(DIGEST_AUTH_PASSWORD);
        
        fetcher().getCredentialStore().getCredentials().put("digest-auth-credential",
                digestAuthCred);

        CrawlURI curi = makeCrawlURI("http://localhost:7778/auth/1");
        fetcher().process(curi);

        // check that we got the expected response and the fetcher did its thing
        assertEquals(401, curi.getFetchStatus());
        assertTrue(curi.getCredentials().contains(digestAuthCred));
        assertTrue(curi.getHttpAuthChallenges() != null && curi.getHttpAuthChallenges().containsKey("digest"));

        // stick a basic auth 401 in there to check it doesn't mess with the digest auth we're working on
        CrawlURI interferingUri = makeCrawlURI("http://localhost:7777/auth/basic");
        fetcher().process(interferingUri);
        assertEquals(401, interferingUri.getFetchStatus());
        // logger.info('\n' + httpRequestString(interferingUri) + "\n\n" + rawResponseString(interferingUri));

        // fetch original again with the credentials
        fetcher().process(curi);
        String httpRequestString = httpRequestString(curi);
        // logger.info('\n' + httpRequestString + "\n\n" + rawResponseString(interferingUri));
        assertTrue(httpRequestString.contains("Authorization: Digest"));
        // otherwise should be a normal 200 response
        runDefaultChecks(curi, "requestLine", "hostHeader");
        
        // fetch a fresh uri to make sure auth info was cached and we don't get another 401
        curi = makeCrawlURI("http://localhost:7778/auth/2");
        fetcher().process(curi);
        httpRequestString = httpRequestString(curi);
        assertTrue(httpRequestString.contains("Authorization: Digest"));
        // otherwise should be a normal 200 response
        runDefaultChecks(curi, "requestLine", "hostHeader");
    }

    @Test
    public void test401NoChallenge() throws URIException, IOException, InterruptedException {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/401-no-challenge");
        fetcher().process(curi);
        assertEquals(401, curi.getFetchStatus());
        runDefaultChecks(curi, "requestLine", "fetchStatus","nonFatalFailuresIsEmpty");
    }
    
    protected void checkSetCookieURI() throws URIException, IOException,
            InterruptedException, UnsupportedEncodingException {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/set-cookie");
        fetcher().process(curi);
        runDefaultChecks(curi, "requestLine");
        
        // check for set-cookie header
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getReplayInputStream());
        String rawResponseString = new String(buf, "US-ASCII");
        assertTrue(rawResponseString.contains("Set-Cookie: test-cookie-name=test-cookie-value\r\n"));
    }

    @Test
    public void testAcceptCompression() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().setAcceptCompression(true);
        fetcher().process(curi);
        String httpRequestString = httpRequestString(curi);
        // logger.info('\n' + httpRequestString + "\n\n" + rawResponseString(curi));
        // logger.info("\n----- begin messageBodyString -----\n" + messageBodyString(curi));
        // logger.info("\n----- begin entityString -----\n" + entityString(curi));
        // logger.info("\n----- begin contentString -----\n" + contentString(curi));
        assertTrue(httpRequestString.contains("Accept-Encoding: gzip,deflate\r\n"));
        assertEquals(DEFAULT_GZIPPED_PAYLOAD.length, curi.getContentLength());
        assertEquals(curi.getContentSize(), curi.getRecordedSize());

        // check various 
        assertEquals("text/plain;charset=US-ASCII", curi.getContentType());
        assertEquals(Charset.forName("US-ASCII"), curi.getRecorder().getCharset());
        assertTrue(curi.getCredentials().isEmpty());
        assertTrue(curi.getFetchDuration() >= 0);
        assertTrue(curi.getFetchStatus() == 200);
        assertTrue(curi.getFetchType() == FetchType.HTTP_GET);

        // check message body, i.e. "raw, possibly chunked-transfer-encoded message contents not including the leading headers"
        assertTrue(Arrays.equals(DEFAULT_GZIPPED_PAYLOAD, IOUtils.toByteArray(curi.getRecorder().getMessageBodyReplayInputStream())));

        // check entity, i.e. "message-body after any (usually-unnecessary) transfer-decoding but before any content-encoding (eg gzip) decoding"
        assertTrue(Arrays.equals(DEFAULT_GZIPPED_PAYLOAD, IOUtils.toByteArray(curi.getRecorder().getEntityReplayInputStream())));

        // check content, i.e. message-body after possibly tranfer-decoding and after content-encoding (eg gzip) decoding
        assertEquals(DEFAULT_PAYLOAD_STRING, contentString(curi));
        assertEquals("sha1:6HXUWMO6VPBHU4SIPOVJ3OPMCSN6JJW4", curi.getContentDigestSchemeString());
    }

    // Test will succeed if there ae at least 2 local Inet4Addresses, and 
    // each can be bound to in turn. (Works better than trying to use 127.0.0.2
    // which may not be available as local address by default on MacOS.)
    // Usually, the minimum 2 addresses will be 127.0.0.1 and another 
    // routable (perhaps LAN only eg 192.168.x.x or 10.x.x.x) address.
    @Test
    public void testHttpBindAddress() throws Exception {
        List<InetAddress> addrList = new ArrayList<InetAddress>();
        for (NetworkInterface ifc: Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ifc.isUp()) {
                // avoid macOS utun interfaces as at least in the case of wireguard
                // binding to them and then connecting to localhost seems to always
                // give a client address of localhost instead of the interface IP
                if (ifc.getName().startsWith("utun")) continue;

                for (InetAddress addr : Collections.list(ifc.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        addrList.add(addr);
                    }
                }
            }
        }
        if (addrList.size() < 2) {
            fail("unable to test binding to different local addresses: only "
                    + addrList.size() + " addresses available");
        }
        for (InetAddress addr : addrList) {
            tryHttpBindAddress(addr.getHostAddress());
        }
    }

    public void tryHttpBindAddress(String addr) throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().setHttpBindAddress(addr);
        fetcher().process(curi);

        // the client bind address isn't recorded anywhere in heritrix as
        // far as i can tell, so we get the server to echo it back to us...
        assertTrue(rawResponseString(curi).contains("Client-Host: " + addr + "\r\n"));

        runDefaultChecks(curi, "httpBindAddress");
    }

    public static class TestProxyServlet extends ProxyServlet {
        @Override
        protected void onServerResponseHeaders(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse) {
            super.onServerResponseHeaders(clientRequest, proxyResponse, serverResponse);
            proxyResponse.addHeader("Via", "test-proxy-servlet");
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            String expectedUser = (String) getServletContext().getAttribute("proxy-user");
            if (expectedUser != null) {
                String expectedPassword = (String) getServletContext().getAttribute("proxy-password");
                String expectedHeader = "Basic " + Base64.getEncoder().encodeToString((expectedUser + ":" + expectedPassword).getBytes());
                String authHeader = request.getHeader("Proxy-Authorization");
                if (!expectedHeader.equals(authHeader)) {
                    response.setHeader("Proxy-Authenticate", "Basic realm=\"test\"");
                    response.sendError(407);
                    return;
                }
            }
            super.service(request, response);
        }
    }

    private static Server newHttpProxy() {
        return newHttpProxy(null, null);
    }

    private static Server newHttpProxy(String user, String password) {
        Server httpProxyServer = new Server(new InetSocketAddress("localhost", 7877));
        ConnectHandler connectHandler = new ConnectHandler();
        httpProxyServer.setHandler(connectHandler);
        ServletContextHandler context = new ServletContextHandler(connectHandler, "/",
                ServletContextHandler.SESSIONS);
        ServletHolder proxyServlet = new ServletHolder(TestProxyServlet.class);
        context.addServlet(proxyServlet, "/*");
        context.setAttribute("proxy-user", user);
        context.setAttribute("proxy-password", password);
        return httpProxyServer;
    }

    @Test
    public void testHttpProxy() throws Exception {
        Server httpProxyServer = newHttpProxy(null, null);
        httpProxyServer.start();

        try {
            fetcher().setHttpProxyHost("localhost");
            fetcher().setHttpProxyPort(7877);

            CrawlURI curi = makeCrawlURI("http://localhost:7777/");
            fetcher().process(curi);

            String requestString = httpRequestString(curi);
            assertTrue(requestString.startsWith("GET http://localhost:7777/ HTTP/1.0\r\n"));
            assertEquals("test-proxy-servlet", curi.getHttpResponseHeader("Via"));
            
            assertTrue(requestString.contains("Proxy-Connection: close\r\n"));
            
            runDefaultChecks(curi, "requestLine");
        } finally {
            httpProxyServer.stop();
        }
    }

    @Test
    public void testHttpProxyAuth() throws Exception {
        Server httpProxyServer = newHttpProxy("http-proxy-user", "http-proxy-password");
        httpProxyServer.start();

        try {
            fetcher().setHttpProxyHost("localhost");
            fetcher().setHttpProxyPort(7877);
            fetcher().setHttpProxyUser("http-proxy-user");
            fetcher().setHttpProxyPassword("http-proxy-password");
            fetcher().setUseHTTP11(true); // proxy auth is a http 1.1 feature

            CrawlURI curi = makeCrawlURI("http://localhost:7777/");
            fetcher().process(curi);

            String requestString = httpRequestString(curi);
            assertTrue(requestString.startsWith("GET http://localhost:7777/ HTTP/1.1\r\n"));
            assertTrue(requestString.contains("Proxy-Connection: close\r\n"));

            assertNotNull(curi.getHttpResponseHeader("Proxy-Authenticate"));
            assertEquals(407, curi.getFetchStatus());

            // fetch original again now that credentials should be populated
            curi = makeCrawlURI("http://localhost:7777/");
            fetcher().process(curi);

            requestString = httpRequestString(curi);
            assertTrue(requestString.startsWith("GET http://localhost:7777/ HTTP/1.1\r\n"));
            assertTrue(requestString.contains("Proxy-Connection: close\r\n"));
            assertNotNull(curi.getHttpResponseHeader("Via"));
            runDefaultChecks(curi, "requestLine");
        } finally {
            httpProxyServer.stop();
        }
    }

    @Test
    public void testHttpsProxy() throws Exception {
        Server httpProxyServer = newHttpProxy();
        httpProxyServer.start();

        try {
            fetcher().setHttpProxyHost("localhost");
            fetcher().setHttpProxyPort(7877);

            CrawlURI curi = makeCrawlURI("https://localhost:7443/");
            fetcher().process(curi);

            String requestString = httpRequestString(curi);
            assertTrue(requestString.contains("Host: localhost:7443\r\n"));
            // in case of HTTPS we do not have a "Via" header in the recorded request
            // as this is only present in the "CONNECT" that we do not record
            assertNull(curi.getHttpResponseHeader("Via"));
            runDefaultChecks(curi, "hostHeader");
        } finally {
            httpProxyServer.stop();
        }
    }

    @Test
    public void testMaxFetchKBSec() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/200k");
        fetcher().setMaxFetchKBSec(100);
        
        // if the wire logger is enabled, it can slow things down enough to make
        // this test failed, so disable it temporarily
        Level savedWireLevel = Logger.getLogger("org.apache.http.wire").getLevel();
        Logger.getLogger("org.apache.http.wire").setLevel(Level.INFO);
        
        fetcher().process(curi);
        
        Logger.getLogger("org.apache.http.wire").setLevel(savedWireLevel);
        
        assertEquals(200000, curi.getContentLength());
        assertTrue(curi.getFetchDuration() > 1800 && curi.getFetchDuration() < 2200);
    }

    @Test
    public void testMaxLengthBytes() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/200k");
        fetcher().setMaxLengthBytes(50000);
        fetcher().process(curi);
        assertEquals(50001, curi.getRecordedSize());
    }

    @Test
    public void testSendRange() throws Exception { 
        CrawlURI curi = makeCrawlURI("http://localhost:7777/200k");
        fetcher().setMaxLengthBytes(50000);
        fetcher().setSendRange(true);
        fetcher().process(curi);
        // logger.info("\n" + httpRequestString(curi));
        assertTrue(httpRequestString(curi).contains("Range: bytes=0-49999\r\n"));
        // XXX make server honor range and inspect response?
        // assertEquals(50000, curi.getRecordedSize());
    }

    @Test
    public void testSendIfModifiedSince() throws Exception {
        fetcher().setSendIfModifiedSince(true);

        CrawlURI curi = makeCrawlURI("http://localhost:7777/if-modified-since");
        fetcher().process(curi);
        assertFalse(httpRequestString(curi).toLowerCase().contains("if-modified-since: "));
        assertTrue(curi.getHttpResponseHeader("last-modified").equals("Thu, 01 Jan 1970 00:00:00 GMT"));
        runDefaultChecks(curi, "requestLine");

        // logger.info("before FetchHistoryProcessor fetchHistory=" + Arrays.toString(curi.getFetchHistory()));
        FetchHistoryProcessor fetchHistoryProcessor = new FetchHistoryProcessor();
        fetchHistoryProcessor.process(curi);
        // logger.info("after FetchHistoryProcessor fetchHistory=" + Arrays.toString(curi.getFetchHistory()));

        fetcher().process(curi);
        // logger.info("\n" + httpRequestString(curi));
        // logger.info("\n" + rawResponseString(curi));
        assertTrue(httpRequestString(curi).contains("If-Modified-Since: Thu, 01 Jan 1970 00:00:00 GMT\r\n"));
        assertTrue(curi.getFetchStatus() == 304);

        assertNull(curi.getRevisitProfile());
        fetchHistoryProcessor.process(curi);
        assertNotNull(curi.getRevisitProfile());
        assertTrue(curi.getRevisitProfile() instanceof ServerNotModifiedRevisit);
        ServerNotModifiedRevisit revisit = (ServerNotModifiedRevisit) curi.getRevisitProfile();
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", revisit.getLastModified());
        assertNull(revisit.getETag());
    }

    @Test
    public void testSendIfNoneMatch() throws Exception {
        fetcher().setSendIfNoneMatch(true);
        
        CrawlURI curi = makeCrawlURI("http://localhost:7777/if-none-match");
        fetcher().process(curi);
        assertFalse(httpRequestString(curi).toLowerCase().contains("if-none-match: "));
        assertTrue(curi.getHttpResponseHeader("etag").equals(ETAG_TEST_VALUE));
        runDefaultChecks(curi, "requestLine");

        FetchHistoryProcessor fetchHistoryProcessor = new FetchHistoryProcessor();
        fetchHistoryProcessor.process(curi);

        fetcher().process(curi);
        // logger.info("\n" + httpRequestString(curi));
        // logger.info("\n" + rawResponseString(curi));
        assertTrue(httpRequestString(curi).contains("If-None-Match: " + ETAG_TEST_VALUE + "\r\n"));
        
        assertNull(curi.getRevisitProfile());
        fetchHistoryProcessor.process(curi);
        assertNotNull(curi.getRevisitProfile());
        assertTrue(curi.getRevisitProfile() instanceof ServerNotModifiedRevisit);
        ServerNotModifiedRevisit revisit = (ServerNotModifiedRevisit) curi.getRevisitProfile();
        assertEquals(ETAG_TEST_VALUE, revisit.getETag());
        assertNull(revisit.getLastModified());

    }

    @Test
    public void testShouldFetchBodyRule() throws Exception {
        // CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        CrawlURI curi = makeCrawlURI("http://localhost:7777/200k");
        fetcher().setShouldFetchBodyRule(new RejectDecideRule());
        fetcher().process(curi);

        assertTrue(httpRequestString(curi).startsWith("GET /200k HTTP/1.0\r\n"));
        assertEquals("text/plain;charset=US-ASCII", curi.getContentType());
        assertTrue(curi.getCredentials().isEmpty());
        assertTrue(curi.getFetchDuration() >= 0);
        assertTrue(curi.getFetchStatus() == 200);
        assertTrue(curi.getFetchType() == FetchType.HTTP_GET);
        
        assertEquals(1, curi.getAnnotations().size());
        assertTrue(curi.getAnnotations().contains("midFetchAbort"));
        
        // check for empty body
        assertEquals(0, curi.getContentLength());
        assertEquals(curi.getContentSize(), curi.getRecordedSize());
        assertEquals("", messageBodyString(curi));
        assertEquals("", entityString(curi));
    }

    @Test
    public void testFetchTimeout() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/slow.txt");
        fetcher().setTimeoutSeconds(2);
        fetcher().process(curi);
        
        // logger.info('\n' + httpRequestString(curi) + "\n\n" + rawResponseString(curi));
        assertTrue(curi.getAnnotations().contains("timeTrunc"));
        assertTrue(curi.getFetchDuration() >= 2000 && curi.getFetchDuration() < 2200);
    }

    /*
     * See http://stackoverflow.com/questions/100841/artificially-create-a-connection-timeout-error 
     * This test seems to fail if not connected to internet, because 
     * connection fails immediately insted of timing out.
     */
    @Test
    public void testConnectionTimeout() throws Exception {
        CrawlURI curi = makeCrawlURI("http://10.255.255.1/");
        fetcher().setSoTimeoutMs(300);
        
        long start = System.currentTimeMillis();
        fetcher().process(curi);
        long elapsed = System.currentTimeMillis() - start;
        
        assertTrue(elapsed >= 300 && elapsed < 400);
        
        // Httpcomponents throws org.apache.http.conn.ConnectTimeoutException,
        // commons-httpclient throws java.net.SocketTimeoutException. Both are
        // instances of InterruptedIOException
        assertEquals(1, curi.getNonFatalFailures().size());
        assertTrue(curi.getNonFatalFailures().toArray()[0] instanceof InterruptedIOException);
        assertTrue(curi.getNonFatalFailures().toArray()[0].toString().matches("(?i).*connect.*timed out.*"));

        assertEquals(FetchStatusCodes.S_CONNECT_FAILED, curi.getFetchStatus());
        
        assertEquals(0, curi.getFetchCompletedTime());
    }
    
    // XXX testSocketTimeout() (the other kind) - how to simulate?

    @Test
    public void testSslTrustLevel() throws Exception {
        // default "open" trust level
        CrawlURI curi = makeCrawlURI("https://localhost:7443/");
        fetcher().process(curi);
        runDefaultChecks(curi, "hostHeader");
        
        // "normal" trust level
        curi = makeCrawlURI("https://localhost:7443/");
        fetcher().setSslTrustLevel(TrustLevel.NORMAL);
        fetcher().process(curi);
        assertEquals(1, curi.getNonFatalFailures().size());
        assertTrue(curi.getNonFatalFailures().toArray()[0] instanceof SSLException);
        assertEquals(FetchStatusCodes.S_CONNECT_FAILED, curi.getFetchStatus());
        assertEquals(0, curi.getFetchCompletedTime());
    }

    @Test
    public void testHttp11() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        fetcher().setUseHTTP11(true);
        fetcher().process(curi);
        assertTrue(httpRequestString(curi).startsWith("GET / HTTP/1.1\r\n"));
        // what else?
        runDefaultChecks(curi, "requestLine");
    }

    @Test
    public void testChunked() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/chunked.txt");
        fetcher().setUseHTTP11(true);
        fetcher().setSendConnectionClose(false);
        
        /* XXX Server expects us to close the connection apparently. But we
         * don't detect end of chunked transfer. With these small timeouts we
         * can finish quickly. A couple of SocketTimeoutExceptions will happen
         * within RecordingInputStream.readFullyOrUntil().
         */
        fetcher().setSoTimeoutMs(500);
        fetcher().setTimeoutSeconds(1);
        
        fetcher().process(curi);
        
        //        logger.info('\n' + httpRequestString(curi) + "\n\n" + rawResponseString(curi));
        //        logger.info("\n----- rawResponseString -----\n" + rawResponseString(curi));
        //        logger.info("\n----- contentString -----\n" + contentString(curi));
        //        logger.info("\n----- entityString -----\n" + entityString(curi));
        //        logger.info("\n----- messageBodyString -----\n" + messageBodyString(curi));
        
        assertEquals("chunked", curi.getHttpResponseHeader("transfer-encoding"));
        assertEquals("25\r\n" + DEFAULT_PAYLOAD_STRING + "\r\n0\r\n\r\n", messageBodyString(curi));
        assertEquals(DEFAULT_PAYLOAD_STRING, entityString(curi));
        assertEquals(DEFAULT_PAYLOAD_STRING, contentString(curi));
    }

    protected static class NoResponseServer extends Thread {
        protected String listenAddress;
        protected int listenPort;
        protected boolean isTimeToBeDone = false;

        public NoResponseServer(String address, int port) {
            this.listenAddress = address;
            this.listenPort = port;
        }

        @Override
        public void run() {
            ServerSocket listeningSocket = null;
            try {
                listeningSocket  = new ServerSocket(listenPort, 0, Inet4Address.getByName(listenAddress));
                listeningSocket.setSoTimeout(600);
                while (!isTimeToBeDone) {
                    try {
                        Socket connectionSocket = listeningSocket.accept();
                        // logger.info("accepted connection from " + connectionSocket + ", shutting it down immediately");
                        connectionSocket.shutdownOutput();
                    } catch (SocketTimeoutException e) {
                    }
                }
            } catch (Exception e) {
                // logger.warning("caught exception: " + e);
            } finally {
                // logger.info("all done suckers");
                if (listeningSocket != null) {
                    try {
                        listeningSocket.close();
                    } catch (IOException e) {
                    }
                }
            }
        }

        public void beDone() {
            isTimeToBeDone = true;
        }
    }

    // Implicitly tests the retry cycle within httpcomponents
    @Test
    public void testNoResponse() throws Exception {
        NoResponseServer noResponseServer = new NoResponseServer("localhost", 7780);
        noResponseServer.start();

        // Give the server time to start up:
        Thread.sleep(1000);
        
        // CrawlURI curi = makeCrawlURI("http://stats.bbc.co.uk/robots.txt");
        CrawlURI curi = makeCrawlURI("http://localhost:7780");
        fetcher().process(curi);
        assertEquals(1, curi.getNonFatalFailures().size());
        assertTrue(curi.getNonFatalFailures().toArray()[0] instanceof NoHttpResponseException);
        assertEquals(FetchStatusCodes.S_CONNECT_FAILED, curi.getFetchStatus());
        assertEquals(0, curi.getFetchCompletedTime());
        
        noResponseServer.beDone();
        noResponseServer.join();
    }
    
    /**
     * Tests a URL not correctly url-encoded, but that heritrix lets pass
     * through to mimic browser behavior. {@link java.net.URI} would reject this
     * url. See class comment on {@link UURI}.
     * 
     * @throws Exception
     */
    @Test
    public void testLaxUrlEncoding() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/99%");
        fetcher().process(curi);
        // logger.info('\n' + httpRequestString(curi) + "\n\n" + rawResponseString(curi));
        assertTrue(httpRequestString(curi).startsWith("GET /99% HTTP/1.0\r\n"));
        // jetty 9 rejects requests with paths like this with 400 Bad Request
        // so we can't run these checks anymore
        //runDefaultChecks(curi, "requestLine");
    }

    @Test
    public void testTwoQuestionMarks() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/??blahblah");
        fetcher().process(curi);
        // logger.info('\n' + httpRequestString(curi) + "\n\n" + rawResponseString(curi));
        assertTrue(httpRequestString(curi).startsWith("GET /??blahblah HTTP/1.0\r\n"));
        runDefaultChecks(curi, "requestLine");
    }

    @Test
    public void testUrlWithSpaces() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/url with spaces?query%20with%20spaces");
        fetcher().process(curi);
        assertTrue(httpRequestString(curi).startsWith("GET /url%20with%20spaces?query%20with%20spaces HTTP/1.0\r\n"));
        runDefaultChecks(curi, "requestLine");

        curi = makeCrawlURI("http://localhost:7777/url%20with%20spaces?query with spaces");
        fetcher().process(curi);
        assertTrue(httpRequestString(curi).startsWith("GET /url%20with%20spaces?query%20with%20spaces HTTP/1.0\r\n"));
        runDefaultChecks(curi, "requestLine");
    }

    @Test
    public void testCharsets() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/cp1251");
        fetcher().process(curi);
        assertEquals("text/plain;charset=cp1251", curi.getHttpResponseHeader("content-type"));
        assertEquals(Charset.forName("cp1251"), curi.getRecorder().getCharset());
        assertTrue(Arrays.equals(FetchHTTPTestServers.CP1251_PAYLOAD, IOUtils.toByteArray(curi.getRecorder().getContentReplayInputStream())));
        assertEquals("\u041A\u043E\u0447\u0430\u043D\u0438 \u041E\u0440\u043A"
                + "\u0435\u0441\u0442\u0430\u0440 \u0435 \u0435\u0434\u0435"
                + "\u043D \u043E\u0434 \u043D\u0430\u0458\u043F\u043E\u0437"
                + "\u043D\u0430\u0442\u0438\u0442\u0435 \u0438 \u043D\u0430"
                + "\u0458\u043F\u043E\u043F\u0443\u043B\u0430\u0440\u043D"
                + "\u0438\u0442\u0435 \u0431\u043B\u0435\u0445-\u043E\u0440"
                + "\u043A\u0435\u0441\u0442\u0440\u0438 \u0432\u043E \u0441"
                + "\u0432\u0435\u0442\u043E\u0442, \u043A\u043E\u0458 \u0433"
                + "\u043E \u0441\u043E\u0447\u0438\u043D\u0443\u0432\u0430"
                + "\u0430\u0442 \u0434\u0435\u0441\u0435\u0442\u043C\u0438"
                + "\u043D\u0430 \u0420\u043E\u043C\u0438-\u041C\u0430\u043A"
                + "\u0435\u0434\u043E\u043D\u0446\u0438 \u043F\u043E \u043F"
                + "\u043E\u0442\u0435\u043A\u043B\u043E \u043E\u0434 \u041A"
                + "\u043E\u0447\u0430\u043D\u0438, \u043F\u0440\u0435\u0434"
                + "\u0432\u043E\u0434\u0435\u043D\u0438 \u043E\u0434 \u0442"
                + "\u0440\u0443\u0431\u0430\u0447\u043E\u0442 \u041D\u0430"
                + "\u0430\u0442 (\u041D\u0435\u0430\u0442) \u0412\u0435\u043B"
                + "\u0438\u043E\u0432.\n", 
                curi.getRecorder().getContentReplayCharSequence().toString());

        curi = makeCrawlURI("http://localhost:7777/unsupported-charset");
        fetcher().process(curi);
        assertEquals("text/plain;charset=UNSUPPORTED-CHARSET", curi.getHttpResponseHeader("content-type"));
        assertTrue(curi.getAnnotations().contains("unsatisfiableCharsetInHeader:UNSUPPORTED-CHARSET"));
        assertEquals(Charset.forName("latin1"), curi.getRecorder().getCharset()); // default fallback
        runDefaultChecks(curi, "requestLine", "contentType");
        
        curi = makeCrawlURI("http://localhost:7777/invalid-charset");
        fetcher().process(curi);
        assertEquals("text/plain;charset=%%INVALID-CHARSET%%", curi.getHttpResponseHeader("content-type"));
        assertTrue(curi.getAnnotations().contains("unsatisfiableCharsetInHeader:%%INVALID-CHARSET%%"));
        assertEquals(Charset.forName("latin1"), curi.getRecorder().getCharset()); // default fallback
        runDefaultChecks(curi, "requestLine", "contentType");
    }

    // see https://webarchive.jira.com/browse/HER-2063
    @Test
    @Ignore("Relies on external service and can randomly fail")
    public void testHostHeaderDefaultPort() throws Exception {
        CrawlURI curi = makeCrawlURI("http://example.com/");
        fetcher().process(curi);
        assertTrue(httpRequestString(curi).contains("Host: example.com\r\n"));

        curi = makeCrawlURI("https://example.com/");
        fetcher().process(curi);
        assertTrue(httpRequestString(curi).contains("Host: example.com\r\n"));
    }

    @Test
    public void testHttpPost() throws Exception {
        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        curi.setFetchType(FetchType.HTTP_POST);

        List<NameValue> params = new LinkedList<NameValue>();
        params.add(new NameValue("name1", "value1"));
        params.add(new NameValue("name1", "value2"));
        params.add(new NameValue("funky name 2", "whoa crazy\t && 🍺 🍻 \n crazier \rooo"));
        curi.getData().put(CoreAttributeConstants.A_SUBMIT_DATA, params); 

        fetcher().process(curi);

        assertTrue(httpRequestString(curi).startsWith("POST / HTTP/1.0\r\n"));
        assertTrue(httpRequestString(curi).endsWith("\r\n\r\nname1=value1&name1=value2&funky+name+2=whoa+crazy%09+%26%26+%F0%9F%8D%BA+%F0%9F%8D%BB+%0A+crazier+%0Dooo"));
        assertEquals(FetchType.HTTP_POST, curi.getFetchType());
        runDefaultChecks(curi, "requestLine", "trailingCRLFCRLF", "fetchTypeGET");
    }

    /**
     * Tests support for SOCKS5 proxies by using a temporary SOCKS5 server and routing traffic through it.
     * @throws Exception
     */
    @Test
    public void testSocksProxy() throws Exception {
        // create a fetcher with socks enabled
        FetchHTTP socksFetcher = newSocksTestFetchHttp(getUserAgentString(), "localhost", 7800);

        CrawlURI curi = makeCrawlURI("http://localhost:7777/");
        socksFetcher().process(curi);
        runDefaultChecks(curi);

        socksServer.stop();
    }

    protected FetchHTTP makeModule() throws IOException {
        FetchHTTP fetchHttp = newTestFetchHttp(getUserAgentString());
        fetchHttp.start();
        return fetchHttp;
    }
    
    public static FetchHTTP newTestFetchHttp(String userAgentString) {
        FetchHTTP fetchHttp = new FetchHTTP();
        fetchHttp.setCookieStore(new SimpleCookieStore());
        fetchHttp.setServerCache(new DefaultServerCache());
        CrawlMetadata uap = new CrawlMetadata();
        uap.setUserAgentTemplate(userAgentString);
        fetchHttp.setUserAgentProvider(uap);

        fetchHttp.start();
        return fetchHttp;
    }

    protected FetchHTTP makeSocksModule() throws Exception {
        // configure our test server
        socksServer = new SocksServer(7800);


        // SocksServers opens the socket on a background thread, so we supply a socket factory
        // wrapper to let us block until the socket is opened and to report any exception that
        // occurs when binding. This works around a race where we connect to the server before
        // it is listening.
        CompletableFuture<Void> socksServerStartedFuture = new CompletableFuture<>();
        socksServer.setFactory(new ServerSocketFactory() {
            ServerSocketFactory defaultSocketFactory = ServerSocketFactory.getDefault();

            @Override
            public ServerSocket createServerSocket(int port) throws IOException {
                return createServerSocket(port, -1);
            }

            @Override
            public ServerSocket createServerSocket(int port, int backlog) throws IOException {
                return createServerSocket(port, backlog, null);
            }

            @Override
            public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
                try {
                    ServerSocket socket = defaultSocketFactory.createServerSocket(port, backlog, ifAddress);
                    socksServerStartedFuture.complete(null);
                    return socket;
                } catch (Throwable e) {
                    socksServerStartedFuture.completeExceptionally(e);
                    throw e;
                }
            }
        });
        socksServer.start();
        socksServerStartedFuture.get(30, TimeUnit.SECONDS);

        FetchHTTP fetchHttp = newSocksTestFetchHttp(getUserAgentString(), "localhost", 7800);
        fetchHttp.start();

        return fetchHttp;
    }
    
    public static FetchHTTP newSocksTestFetchHttp(String userAgentString, String socksProxyHost, Integer socksProxyPort) {
        FetchHTTP fetchHttp = new FetchHTTP();
        fetchHttp.setCookieStore(new SimpleCookieStore());
        fetchHttp.setServerCache(new DefaultServerCache());
        fetchHttp.setSocksProxyHost(socksProxyHost);
        fetchHttp.setSocksProxyPort(socksProxyPort);
        CrawlMetadata uap = new CrawlMetadata();
        uap.setUserAgentTemplate(userAgentString);
        fetchHttp.setUserAgentProvider(uap);

        fetchHttp.start();
        return fetchHttp;
    }

    @After
    public void tearDown() throws Exception {
        if (fetcher != null) {
            fetcher.stop();
            fetcher = null;
        }

        if (socksFetcher != null) {
            socksFetcher.stop();
            socksFetcher = null;
        }

        if (socksServer != null) {
            socksServer.stop();
            socksServer = null;
        }
    }

    @BeforeClass
    public static void setUpServers() throws Exception {
        FetchHTTPTestServers.ensureHttpServers();
    }

    @AfterClass
    public static void tearDownServers() throws Exception {
        FetchHTTPTestServers.stopHttpServers();
    }

    protected Recorder getRecorder() throws IOException {
        if (Recorder.getHttpRecorder() == null) {
            Recorder httpRecorder = new Recorder(TmpDirTestCase.tmpDir(),
                    getClass().getName(), 16 * 1024, 512 * 1024);
            Recorder.setHttpRecorder(httpRecorder);
        }

        return Recorder.getHttpRecorder();
    }

    protected CrawlURI makeCrawlURI(String uri) throws URIException,
            IOException {
        UURI uuri = UURIFactory.getInstance(uri);
        CrawlURI curi = new CrawlURI(uuri);
        curi.setSeed(true);
        curi.setRecorder(getRecorder());
        return curi;
    }
}

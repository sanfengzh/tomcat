/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
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
package org.apache.coyote.http2;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.catalina.util.IOTools;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.coyote.http2.Http2Parser.Input;
import org.apache.coyote.http2.Http2Parser.Output;
import org.apache.tomcat.util.codec.binary.Base64;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.TesterSupport;
import org.junit.Assert;
import org.junit.Assume;

import javax.net.SocketFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Tests for compliance with the <a href="https://tools.ietf.org/html/rfc7540">
 * HTTP/2 specification</a>.
 */
public abstract class Http2TestBase extends TomcatBaseTest {

    // Nothing special about this date apart from it being the date I ran the
    // test that demonstrated that most HTTP/2 tests were failing because the
    // response now included a date header
    protected static final String DEFAULT_DATE = "Wed, 11 Nov 2015 19:18:42 GMT";
    protected static final long DEFAULT_TIME = FastHttpDateFormat.parseDate(DEFAULT_DATE);

    private static final String HEADER_IGNORED = "x-ignore";

    static final String DEFAULT_CONNECTION_HEADER_VALUE = "Upgrade, HTTP2-Settings";
    private static final byte[] EMPTY_SETTINGS_FRAME =
        { 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00 };
    static final String EMPTY_HTTP2_SETTINGS_HEADER;

    static {
        byte[] empty = new byte[0];
        EMPTY_HTTP2_SETTINGS_HEADER = "HTTP2-Settings: " + Base64.encodeBase64URLSafeString(empty) + "\r\n";
    }

    protected static final String TRAILER_HEADER_NAME = "X-TrailerTest";
    protected static final String TRAILER_HEADER_VALUE = "test";

    // Client
    private Socket s;
    protected HpackEncoder hpackEncoder;
    protected Input input;
    protected TestOutput output;
    protected Http2Parser parser;
    protected OutputStream os;

    // Server
    protected Http2Protocol http2Protocol;

    private long pingAckDelayMillis = 0;


    protected void setPingAckDelayMillis(long delay) {
        pingAckDelayMillis = delay;
    }

    /**
     * Standard setup. Creates HTTP/2 connection via HTTP upgrade and ensures
     * that the first response is correctly received.
     */
    protected void http2Connect() throws Exception {
        http2Connect(false);
    }

    protected void http2Connect(boolean tls) throws Exception {
        enableHttp2(tls);
        configureAndStartWebApplication();
        openClientConnection(tls);
        doHttpUpgrade();
        sendClientPreface();
        validateHttp2InitialResponse();
    }


    protected void validateHttp2InitialResponse() throws Exception {
        // - 101 response acts as acknowledgement of the HTTP2-Settings header
        // Need to read 5 frames
        // - settings (server settings - must be first)
        // - settings ack (for the settings frame in the client preface)
        // - ping
        // - headers (for response)
        // - data (for response body)
        parser.readFrame(true);
        parser.readFrame(true);
        parser.readFrame(true);
        parser.readFrame(true);
        parser.readFrame(true);

        Assert.assertEquals("0-Settings-[3]-[200]\n" +
                "0-Settings-End\n" +
                "0-Settings-Ack\n" +
                "0-Ping-[0,0,0,0,0,0,0,1]\n" +
                getSimpleResponseTrace(1)
                , output.getTrace());
        output.clearTrace();
    }


    protected void sendEmptyGetRequest(int streamId) throws IOException {
        byte[] frameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);

        buildEmptyGetRequest(frameHeader, headersPayload, null, streamId);
        writeFrame(frameHeader, headersPayload);
    }


    protected void sendSimpleGetRequest(int streamId) throws IOException {
        sendSimpleGetRequest(streamId, null);
    }


    protected void sendSimpleGetRequest(int streamId, byte[] padding) throws IOException {
        byte[] frameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);

        buildSimpleGetRequest(frameHeader, headersPayload, padding, streamId);
        writeFrame(frameHeader, headersPayload);
    }


    protected void sendLargeGetRequest(int streamId) throws IOException {
        byte[] frameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);

        buildLargeGetRequest(frameHeader, headersPayload, streamId);
        writeFrame(frameHeader, headersPayload);
    }


    protected void buildEmptyGetRequest(byte[] frameHeader, ByteBuffer headersPayload,
            byte[] padding, int streamId) {
        buildGetRequest(frameHeader, headersPayload, padding, streamId, "/empty");
    }


    protected void buildSimpleGetRequest(byte[] frameHeader, ByteBuffer headersPayload,
            byte[] padding, int streamId) {
        buildGetRequest(frameHeader, headersPayload, padding, streamId, "/simple");
    }


    protected void buildLargeGetRequest(byte[] frameHeader, ByteBuffer headersPayload, int streamId) {
        buildGetRequest(frameHeader, headersPayload, null, streamId, "/large");
    }


    protected void buildGetRequest(byte[] frameHeader, ByteBuffer headersPayload, byte[] padding,
            int streamId, String url) {
        List<Header> headers = new ArrayList<>(3);
        headers.add(new Header(":method", "GET"));
        headers.add(new Header(":scheme", "http"));
        headers.add(new Header(":path", url));
        headers.add(new Header(":authority", "localhost:" + getPort()));

        buildGetRequest(frameHeader, headersPayload, padding, headers, streamId);
    }


    protected void buildGetRequest(byte[] frameHeader, ByteBuffer headersPayload, byte[] padding,
            List<Header> headers, int streamId) {
        if (padding != null) {
            headersPayload.put((byte) (0xFF & padding.length));
        }
        MimeHeaders mimeHeaders = new MimeHeaders();
        for (Header header : headers) {
            mimeHeaders.addValue(header.getName()).setString(header.getValue());
        }
        hpackEncoder.encode(mimeHeaders, headersPayload);
        if (padding != null) {
            headersPayload.put(padding);
        }
        headersPayload.flip();

        ByteUtil.setThreeBytes(frameHeader, 0, headersPayload.limit());
        frameHeader[3] = FrameType.HEADERS.getIdByte();
        // Flags. end of headers (0x04). end of stream (0x01)
        frameHeader[4] = 0x05;
        if (padding != null) {
            frameHeader[4] += 0x08;
        }
        // Stream id
        ByteUtil.set31Bits(frameHeader, 5, streamId);
    }


    protected void buildSimpleGetRequestPart1(byte[] frameHeader, ByteBuffer headersPayload,
            int streamId) {
        List<Header> headers = new ArrayList<>(3);
        headers.add(new Header(":method", "GET"));
        headers.add(new Header(":scheme", "http"));
        headers.add(new Header(":path", "/simple"));

        buildSimpleGetRequestPart1(frameHeader, headersPayload, headers, streamId);
    }


    protected void buildSimpleGetRequestPart1(byte[] frameHeader, ByteBuffer headersPayload,
            List<Header> headers, int streamId) {
        MimeHeaders mimeHeaders = new MimeHeaders();
        for (Header header : headers) {
            mimeHeaders.addValue(header.getName()).setString(header.getValue());
        }
        hpackEncoder.encode(mimeHeaders, headersPayload);

        headersPayload.flip();

        ByteUtil.setThreeBytes(frameHeader, 0, headersPayload.limit());
        frameHeader[3] = FrameType.HEADERS.getIdByte();
        // Flags. end of stream (0x01)
        frameHeader[4] = 0x01;
        // Stream id
        ByteUtil.set31Bits(frameHeader, 5, streamId);
    }


    protected void buildSimpleGetRequestPart2(byte[] frameHeader, ByteBuffer headersPayload,
            int streamId) {
        List<Header> headers = new ArrayList<>(3);
        headers.add(new Header(":authority", "localhost:" + getPort()));

        buildSimpleGetRequestPart2(frameHeader, headersPayload, headers, streamId);
    }


    protected void buildSimpleGetRequestPart2(byte[] frameHeader, ByteBuffer headersPayload,
            List<Header> headers, int streamId) {
        MimeHeaders mimeHeaders = new MimeHeaders();
        for (Header header : headers) {
            mimeHeaders.addValue(header.getName()).setString(header.getValue());
        }
        hpackEncoder.encode(mimeHeaders, headersPayload);

        headersPayload.flip();

        ByteUtil.setThreeBytes(frameHeader, 0, headersPayload.limit());
        frameHeader[3] = FrameType.CONTINUATION.getIdByte();
        // Flags. end of headers (0x04)
        frameHeader[4] = 0x04;
        // Stream id
        ByteUtil.set31Bits(frameHeader, 5, streamId);
    }


    protected void sendSimplePostRequest(int streamId, byte[] padding) throws IOException {
        sendSimplePostRequest(streamId, padding, true);
    }


    protected void sendSimplePostRequest(int streamId, byte[] padding, boolean writeBody)
            throws IOException {
        sendSimplePostRequest(streamId, padding, writeBody, false);
    }

    protected void sendSimplePostRequest(int streamId, byte[] padding, boolean writeBody,
            boolean useExpectation) throws IOException {
        byte[] headersFrameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);
        byte[] dataFrameHeader = new byte[9];
        ByteBuffer dataPayload = ByteBuffer.allocate(128);

        buildPostRequest(headersFrameHeader, headersPayload, useExpectation,
                dataFrameHeader, dataPayload, padding, streamId);
        writeFrame(headersFrameHeader, headersPayload);
        if (writeBody) {
            writeFrame(dataFrameHeader, dataPayload);
        }
    }


    protected void sendParameterPostRequest(int streamId, byte[] padding, String body,
            long contentLength, boolean useExpectation) throws IOException {
        byte[] headersFrameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);
        byte[] dataFrameHeader = new byte[9];
        ByteBuffer dataPayload = ByteBuffer.allocate(128);

        buildPostRequest(headersFrameHeader, headersPayload, useExpectation,
                "application/x-www-form-urlencoded", contentLength, "/parameter", dataFrameHeader,
                dataPayload, padding, null, null, streamId);
        writeFrame(headersFrameHeader, headersPayload);
        if (body != null) {
            dataPayload.put(body.getBytes(StandardCharsets.ISO_8859_1));
            writeFrame(dataFrameHeader, dataPayload);
        }
    }


    protected void buildPostRequest(byte[] headersFrameHeader, ByteBuffer headersPayload,
            boolean useExpectation, byte[] dataFrameHeader, ByteBuffer dataPayload, byte[] padding,
            int streamId) {
        buildPostRequest(headersFrameHeader, headersPayload, useExpectation, dataFrameHeader,
                dataPayload, padding, null, null, streamId);
    }

    protected void buildPostRequest(byte[] headersFrameHeader, ByteBuffer headersPayload,
            boolean useExpectation, byte[] dataFrameHeader, ByteBuffer dataPayload, byte[] padding,
            byte[] trailersFrameHeader, ByteBuffer trailersPayload, int streamId) {
        buildPostRequest(headersFrameHeader, headersPayload, useExpectation, null, -1, "/simple",
                dataFrameHeader, dataPayload, padding, trailersFrameHeader, trailersPayload, streamId);
    }

    protected void buildPostRequest(byte[] headersFrameHeader, ByteBuffer headersPayload,
            boolean useExpectation, String contentType, long contentLength, String path,
            byte[] dataFrameHeader, ByteBuffer dataPayload, byte[] padding,
            byte[] trailersFrameHeader, ByteBuffer trailersPayload, int streamId) {

        MimeHeaders headers = new MimeHeaders();
        headers.addValue(":method").setString("POST");
        headers.addValue(":scheme").setString("http");
        headers.addValue(":path").setString(path);
        headers.addValue(":authority").setString("localhost:" + getPort());
        if (useExpectation) {
            headers.addValue("expect").setString("100-continue");
        }
        if (contentType != null) {
            headers.addValue("content-type").setString(contentType);
        }
        if (contentLength > -1) {
            headers.addValue("content-length").setLong(contentLength);
        }
        hpackEncoder.encode(headers, headersPayload);

        headersPayload.flip();

        ByteUtil.setThreeBytes(headersFrameHeader, 0, headersPayload.limit());
        headersFrameHeader[3] = FrameType.HEADERS.getIdByte();
        // Flags. end of headers (0x04)
        headersFrameHeader[4] = 0x04;
        // Stream id
        ByteUtil.set31Bits(headersFrameHeader, 5, streamId);

        // Data
        if (padding != null) {
            dataPayload.put((byte) (padding.length & 0xFF));
            dataPayload.limit(dataPayload.capacity() - padding.length);
        }

        while (dataPayload.hasRemaining()) {
            dataPayload.put((byte) 'x');
        }
        if (padding != null && padding.length > 0) {
            dataPayload.limit(dataPayload.capacity());
            dataPayload.put(padding);
        }

        dataPayload.flip();

        // Size
        ByteUtil.setThreeBytes(dataFrameHeader, 0, dataPayload.limit());
        // Data is type 0
        // Flags: End of stream 1, Padding 8
        if (trailersPayload == null) {
            dataFrameHeader[4] = 0x01;
        } else {
            dataFrameHeader[4] = 0x00;
        }
        if (padding != null) {
            dataFrameHeader[4] += 0x08;
        }
        ByteUtil.set31Bits(dataFrameHeader, 5, streamId);

        // Trailers
        if (trailersPayload != null) {
            MimeHeaders trailerHeaders = new MimeHeaders();
            trailerHeaders.addValue(TRAILER_HEADER_NAME).setString(TRAILER_HEADER_VALUE);
            hpackEncoder.encode(trailerHeaders, trailersPayload);

            trailersPayload.flip();

            ByteUtil.setThreeBytes(trailersFrameHeader, 0, trailersPayload.limit());
            trailersFrameHeader[3] = FrameType.HEADERS.getIdByte();
            // Flags. end of headers (0x04) and end of stream (0x01)
            trailersFrameHeader[4] = 0x05;
            // Stream id
            ByteUtil.set31Bits(trailersFrameHeader, 5, streamId);
        }
    }


    protected void writeFrame(byte[] header, ByteBuffer payload)
            throws IOException {
        writeFrame(header, payload, 0, payload.limit());
    }


    protected void writeFrame(byte[] header, ByteBuffer payload, int offset, int len)
            throws IOException {
        writeFrame(header, payload, offset, len, 0);
    }


    protected void writeFrame(byte[] header, ByteBuffer payload, int offset, int len, int delayms)
            throws IOException {
        os.write(header);
        os.write(payload.array(), payload.arrayOffset() + offset, len);
        os.flush();
        if (delayms > 0) {
            try {
                Thread.sleep(delayms);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }


    protected void readSimpleGetResponse() throws Http2Exception, IOException {
        // Headers
        parser.readFrame(true);
        // Body
        parser.readFrame(true);
    }


    protected void readSimplePostResponse(boolean padding) throws Http2Exception, IOException {
        if (padding) {
            // Window updates for padding
            parser.readFrame(true);
            parser.readFrame(true);
        }
        // Connection window update after reading request body
        parser.readFrame(true);
        // Stream window update after reading request body
        parser.readFrame(true);
        // Headers
        parser.readFrame(true);
        // Body
        parser.readFrame(true);
    }


    protected String getEmptyResponseTrace(int streamId) {
        return getResponseBodyFrameTrace(streamId, "0");
    }


    protected String getSimpleResponseTrace(int streamId) {
        return getResponseBodyFrameTrace(streamId, "8192");
    }


    protected String getCookieResponseTrace(int streamId, int cookieCount) {
        return getResponseBodyFrameTrace(streamId, 200, "text/plain;charset=UTF-8", null,
                "Cookie count: " + cookieCount, null);
    }


    protected String getResponseBodyFrameTrace(int streamId, String body) {
        return getResponseBodyFrameTrace(streamId, 200, "application/octet-stream", null, body, body);
    }


    protected String getResponseBodyFrameTrace(int streamId, int status, String contentType,
            String contentLanguage, String body, String cl) {
        StringBuilder result = new StringBuilder();
        result.append(streamId);
        result.append("-HeadersStart\n");
        result.append(streamId);
        result.append("-Header-[:status]-[");
        result.append(status);
        result.append("]\n");
        result.append(streamId);
        result.append("-Header-[content-type]-[");
        result.append(contentType);
        result.append("]\n");
        if (contentLanguage != null) {
            result.append(streamId);
            result.append("-Header-[content-language]-[");
            result.append(contentLanguage);
            result.append("]\n");
        }
        if (cl != null) {
            result.append(streamId);
            result.append("-Header-[content-length]-[");
            result.append(cl);
            result.append("]\n");
        }
        result.append(streamId);
        result.append("-Header-[date]-[");
        result.append(DEFAULT_DATE);
        result.append("]\n");
        result.append(streamId);
        result.append("-HeadersEnd\n");
        result.append(streamId);
        result.append("-Body-");
        result.append(body);
        result.append("\n");
        result.append(streamId);
        result.append("-EndOfStream\n");

        return result.toString();
    }


    protected void enableHttp2() {
        enableHttp2(200);
    }

    protected void enableHttp2(long maxConcurrentStreams) {
        enableHttp2(maxConcurrentStreams, false);
    }

    protected void enableHttp2(boolean tls) {
        enableHttp2(200, tls);
    }

    protected void enableHttp2(long maxConcurrentStreams, boolean tls) {
        Tomcat tomcat = getTomcatInstance();
        Connector connector = tomcat.getConnector();
        http2Protocol = new UpgradableHttp2Protocol();
        // Short timeouts for now. May need to increase these for CI systems.
        http2Protocol.setReadTimeout(6000);
        http2Protocol.setWriteTimeout(6000);
        http2Protocol.setKeepAliveTimeout(15000);
        http2Protocol.setStreamReadTimeout(3000);
        http2Protocol.setStreamWriteTimeout(3000);
        http2Protocol.setMaxConcurrentStreams(maxConcurrentStreams);
        connector.addUpgradeProtocol(http2Protocol);
        if (tls) {
            // Enable TLS
            TesterSupport.initSsl(tomcat);
        }
    }

    private class UpgradableHttp2Protocol extends Http2Protocol {
        @Override
        public String getHttpUpgradeName(boolean isSSLEnabled) {
            return "h2c";
        }
    }

    protected void configureAndStartWebApplication() throws LifecycleException {
        Tomcat tomcat = getTomcatInstance();

        Context ctxt = tomcat.addContext("", null);
        Tomcat.addServlet(ctxt, "empty", new EmptyServlet());
        ctxt.addServletMappingDecoded("/empty", "empty");
        Tomcat.addServlet(ctxt, "simple", new SimpleServlet());
        ctxt.addServletMappingDecoded("/simple", "simple");
        Tomcat.addServlet(ctxt, "large", new LargeServlet());
        ctxt.addServletMappingDecoded("/large", "large");
        Tomcat.addServlet(ctxt, "cookie", new CookieServlet());
        ctxt.addServletMappingDecoded("/cookie", "cookie");
        Tomcat.addServlet(ctxt, "parameter", new ParameterServlet());
        ctxt.addServletMappingDecoded("/parameter", "parameter");

        tomcat.start();
    }


    protected void openClientConnection() throws IOException {
        openClientConnection(false);
    }

    protected void openClientConnection(boolean tls) throws IOException {
        SocketFactory socketFactory = tls ? TesterSupport.configureClientSsl() : SocketFactory.getDefault();
        // Open a connection
        s = socketFactory.createSocket("localhost", getPort());
        s.setSoTimeout(30000);

        os = s.getOutputStream();
        InputStream is = s.getInputStream();

        input = new TestInput(is);
        output = new TestOutput();
        parser = new Http2Parser("-1", input, output);
        hpackEncoder = new HpackEncoder();
    }


    protected void doHttpUpgrade() throws IOException {
        doHttpUpgrade(DEFAULT_CONNECTION_HEADER_VALUE, "h2c", EMPTY_HTTP2_SETTINGS_HEADER, true);
    }

    protected void doHttpUpgrade(String connection, String upgrade, String settings,
            boolean validate) throws IOException {
        byte[] upgradeRequest = ("GET /simple HTTP/1.1\r\n" +
                "Host: localhost:" + getPort() + "\r\n" +
                "Connection: "+ connection + "\r\n" +
                "Upgrade: " + upgrade + "\r\n" +
                settings +
                "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        os.write(upgradeRequest);
        os.flush();

        if (validate) {
            Assert.assertTrue("Failed to read HTTP Upgrade response",
                    readHttpUpgradeResponse());
        }
    }


    boolean readHttpUpgradeResponse() throws IOException {
        String[] responseHeaders = readHttpResponseHeaders();

        if (responseHeaders.length < 3) {
            return false;
        }
        if (!responseHeaders[0].startsWith("HTTP/1.1 101 ")) {
            return false;
        }

        if (!validateHeader(responseHeaders, "Connection: Upgrade")) {
            return false;
        }
        if (!validateHeader(responseHeaders, "Upgrade: h2c")) {
            return false;
        }

        return true;
    }


    private boolean validateHeader(String[] responseHeaders, String header) {
        boolean found = false;
        for (String responseHeader : responseHeaders) {
            if (responseHeader.equalsIgnoreCase(header)) {
                found = true;
                break;
            }
        }
        return found;
    }


    String[] readHttpResponseHeaders() throws IOException {
        // Only used by test code so safe to keep this just a little larger than
        // we are expecting.
        ByteBuffer data = ByteBuffer.allocate(256);
        byte[] singleByte = new byte[1];
        // Looking for \r\n\r\n
        int seen = 0;
        while (seen < 4) {
            input.fill(true, singleByte);
            switch (seen) {
            case 0:
            case 2: {
                if (singleByte[0] == '\r') {
                    seen++;
                } else {
                    seen = 0;
                }
                break;
            }
            case 1:
            case 3: {
                if (singleByte[0] == '\n') {
                    seen++;
                } else {
                    seen = 0;
                }
                break;
            }
            }
            data.put(singleByte[0]);
        }

        if (seen != 4) {
            throw new IOException("End of headers not found");
        }

        String response = new String(data.array(), data.arrayOffset(),
                data.arrayOffset() + data.position(), StandardCharsets.ISO_8859_1);

        return response.split("\r\n");
    }


    void parseHttp11Response() throws IOException {
        String[] responseHeaders = readHttpResponseHeaders();
        Assert.assertTrue(responseHeaders[0], responseHeaders[0].startsWith("HTTP/1.1 200 "));

        // Find the content length (chunked responses not handled)
        for (int i = 1; i < responseHeaders.length; i++) {
            if (responseHeaders[i].toLowerCase(Locale.ENGLISH).startsWith("content-length")) {
                String cl = responseHeaders[i];
                int pos = cl.indexOf(':');
                if (pos == -1) {
                    throw new IOException("Invalid: [" + cl + "]");
                }
                int len = Integer.parseInt(cl.substring(pos + 1).trim());
                byte[] content = new byte[len];
                input.fill(true, content);
                return;
            }
        }
        Assert.fail("No content-length in response");
    }


    void sendClientPreface() throws IOException {
        os.write(Http2Parser.CLIENT_PREFACE_START);
        os.write(EMPTY_SETTINGS_FRAME);
        os.flush();
    }


    void sendRst(int streamId, long errorCode) throws IOException {
        byte[] rstFrame = new byte[13];
        // length is always 4
        rstFrame[2] = 0x04;
        rstFrame[3] = FrameType.RST.getIdByte();
        // no flags
        // Stream ID
        ByteUtil.set31Bits(rstFrame, 5, streamId);
        // Payload
        ByteUtil.setFourBytes(rstFrame, 9, errorCode);

        os.write(rstFrame);
        os.flush();
    }


    void sendPing() throws IOException {
        sendPing(0, false, new byte[8]);
    }


    void sendPing(int streamId, boolean ack, byte[] payload) throws IOException {
        if (ack && pingAckDelayMillis > 0) {
            try {
                Thread.sleep(pingAckDelayMillis);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        byte[] pingHeader = new byte[9];
        // length
        ByteUtil.setThreeBytes(pingHeader, 0, payload.length);
        // Type
        pingHeader[3] = FrameType.PING.getIdByte();
        // Flags
        if (ack) {
            ByteUtil.setOneBytes(pingHeader, 4, 0x01);
        }
        // Stream
        ByteUtil.set31Bits(pingHeader, 5, streamId);
        os.write(pingHeader);
        os.write(payload);
        os.flush();
    }


    void sendGoaway(int streamId, int lastStreamId, long errorCode, byte[] debug)
            throws IOException {
        byte[] goawayFrame = new byte[17];
        int len = 8;
        if (debug != null) {
            len += debug.length;
        }
        ByteUtil.setThreeBytes(goawayFrame, 0, len);
        // Type
        goawayFrame[3] = FrameType.GOAWAY.getIdByte();
        // No flags
        // Stream
        ByteUtil.set31Bits(goawayFrame, 5, streamId);
        // Last stream
        ByteUtil.set31Bits(goawayFrame, 9, lastStreamId);
        ByteUtil.setFourBytes(goawayFrame, 13, errorCode);
        os.write(goawayFrame);
        if (debug != null && debug.length > 0) {
            os.write(debug);
        }
        os.flush();
    }


    void sendWindowUpdate(int streamId, int increment) throws IOException {
        byte[] updateFrame = new byte[13];
        // length is always 4
        updateFrame[2] = 0x04;
        updateFrame[3] = FrameType.WINDOW_UPDATE.getIdByte();
        // no flags
        // Stream ID
        ByteUtil.set31Bits(updateFrame, 5, streamId);
        // Payload
        ByteUtil.set31Bits(updateFrame, 9, increment);

        os.write(updateFrame);
        os.flush();
    }


    void sendData(int streamId, byte[] payload) throws IOException {
        byte[] header = new byte[9];
        // length
        ByteUtil.setThreeBytes(header, 0, payload.length);
        // Type is zero
        // No flags
        // Stream ID
        ByteUtil.set31Bits(header, 5, streamId);

        os.write(header);
        os.write(payload);
        os.flush();
    }


    void sendPriority(int streamId, int streamDependencyId, int weight) throws IOException {
        byte[] priorityFrame = new byte[14];
        // length
        ByteUtil.setThreeBytes(priorityFrame, 0, 5);
        // type
        priorityFrame[3] = FrameType.PRIORITY.getIdByte();
        // No flags
        // Stream ID
        ByteUtil.set31Bits(priorityFrame, 5, streamId);

        // Payload
        ByteUtil.set31Bits(priorityFrame, 9, streamDependencyId);
        ByteUtil.setOneBytes(priorityFrame, 13, weight);

        os.write(priorityFrame);
        os.flush();
    }


    void sendSettings(int streamId, boolean ack, SettingValue... settings) throws IOException {
        // length
        int settingsCount;
        if (settings == null) {
            settingsCount = 0;
        } else {
            settingsCount = settings.length;
        }

        byte[] settingFrame = new byte[9 + 6 * settingsCount];

        ByteUtil.setThreeBytes(settingFrame, 0, 6 * settingsCount);
        // type
        settingFrame[3] = FrameType.SETTINGS.getIdByte();

        if (ack) {
            settingFrame[4] = 0x01;
        }

        // Stream
        ByteUtil.set31Bits(settingFrame, 5, streamId);

        // Payload
        for (int i = 0; i < settingsCount; i++) {
            // Stops IDE complaining about possible NPE
            Assert.assertNotNull(settings);
            ByteUtil.setTwoBytes(settingFrame, (i * 6) + 9, settings[i].getSetting());
            ByteUtil.setFourBytes(settingFrame, (i * 6) + 11, settings[i].getValue());
        }

        os.write(settingFrame);
        os.flush();
    }


    void handleGoAwayResponse(int lastStream) throws Http2Exception, IOException {
        handleGoAwayResponse(lastStream, Http2Error.PROTOCOL_ERROR);
    }


    protected void skipWindowSizeFrames() throws Http2Exception, IOException {
        do {
            output.clearTrace();
            parser.readFrame(true);
        } while (output.getTrace().contains("WindowSize"));
    }


    void handleGoAwayResponse(int lastStream, Http2Error expectedError)
            throws Http2Exception, IOException {
        try {
            parser.readFrame(true);

            Assert.assertTrue(output.getTrace(), output.getTrace().startsWith(
                    "0-Goaway-[" + lastStream + "]-[" + expectedError.getCode() + "]-["));
        } catch (SocketException se) {
            // On some platform / Connector combinations (e.g. Windows / NIO2),
            // the TCP connection close will be processed before the client gets
            // a chance to read the connection close frame.
            Tomcat tomcat = getTomcatInstance();
            Connector connector = tomcat.getConnector();

            Assume.assumeTrue("This test is only expected to trigger an exception with NIO2",
                    connector.getProtocolHandlerClassName().contains("Nio2"));

            Assume.assumeTrue("This test is only expected to trigger an exception on Windows",
                    System.getProperty("os.name").startsWith("Windows"));
        }
    }


    private static class TestInput implements Http2Parser.Input {

        private final InputStream is;


        public TestInput(InputStream is) {
            this.is = is;
        }


        @Override
        public boolean fill(boolean block, byte[] data) throws IOException {
            return fill(block, data, 0, data.length);        }


        @Override
        public boolean fill(boolean block, ByteBuffer data, int len) throws IOException {
            boolean result = fill(block, data.array(), data.arrayOffset() + data.position(), len);
            if (result) {
                data.position(data.position() + len);
            }
            return result;
        }


        @Override
        public boolean fill(boolean block, byte[] data, int offset, int length) throws IOException {
            // Note: Block is ignored for this test class. Reads always block.
            int off = offset;
            int len = length;
            while (len > 0) {
                int read = is.read(data, off, len);
                if (read == -1) {
                    throw new IOException("End of input stream");
                }
                off += read;
                len -= read;
            }
            return true;
        }


        @Override
        public int getMaxFrameSize() {
            // Hard-coded to use the default
            return ConnectionSettingsBase.DEFAULT_MAX_FRAME_SIZE;
        }
    }


    class TestOutput implements Output, HeaderEmitter {

        private StringBuffer trace = new StringBuffer();
        private String lastStreamId = "0";
        private ConnectionSettingsRemote remoteSettings = new ConnectionSettingsRemote("-1");
        private boolean traceBody = false;
        private ByteBuffer bodyBuffer = null;
        private long bytesRead;

        public void setTraceBody(boolean traceBody) {
            this.traceBody = traceBody;
        }


        @Override
        public HpackDecoder getHpackDecoder() {
            return new HpackDecoder(remoteSettings.getHeaderTableSize());
        }


        @Override
        public ByteBuffer startRequestBodyFrame(int streamId, int payloadSize, boolean endOfStream) {
            lastStreamId = Integer.toString(streamId);
            bytesRead += payloadSize;
            if (traceBody) {
                bodyBuffer = ByteBuffer.allocate(payloadSize);
                return bodyBuffer;
            } else {
                trace.append(lastStreamId + "-Body-" + payloadSize + "\n");
                return null;
            }
        }


        @Override
        public void endRequestBodyFrame(int streamId) throws Http2Exception {
            if (bodyBuffer != null) {
                if (bodyBuffer.limit() > 0) {
                    trace.append(lastStreamId + "-Body-");
                    bodyBuffer.flip();
                    while (bodyBuffer.hasRemaining()) {
                        trace.append((char) bodyBuffer.get());
                    }
                    trace.append("\n");
                    bodyBuffer = null;
                }
            }
        }


        @Override
        public void receivedEndOfStream(int streamId) {
            lastStreamId = Integer.toString(streamId);
            trace.append(lastStreamId + "-EndOfStream\n");
        }


        @Override
        public HeaderEmitter headersStart(int streamId, boolean headersEndStream) {
            lastStreamId = Integer.toString(streamId);
            trace.append(lastStreamId + "-HeadersStart\n");
            return this;
        }

        @Override
        public void reprioritise(int streamId, int parentStreamId, boolean exclusive, int weight) {
            lastStreamId = Integer.toString(streamId);
            trace.append(lastStreamId + "-Reprioritise-[" + parentStreamId + "]-[" + exclusive +
                    "]-[" + weight + "]\n");
        }


        @Override
        public void emitHeader(String name, String value) {
            if ("date".equals(name)) {
                // Date headers will always change so use a hard-coded default
                value = DEFAULT_DATE;
            } else if ("etag".equals(name) && value.startsWith("W/\"")) {
                // etag headers will vary depending on when the source was
                // checked out, unpacked, copied etc so use the same default as
                // for date headers
                int startOfTime = value.indexOf('-');
                value = value.substring(0, startOfTime + 1) + DEFAULT_TIME + "\"";
            }
            // Some header values vary so ignore them
            if (HEADER_IGNORED.equals(name)) {
                trace.append(lastStreamId + "-Header-[" + name + "]-[...]\n");
            } else {
                trace.append(lastStreamId + "-Header-[" + name + "]-[" + value + "]\n");
            }
        }


        @Override
        public void validateHeaders() {
            // NO-OP: Accept anything the server sends for the unit tests
        }


        @Override
        public void setHeaderException(StreamException streamException) {
            // NO-OP: Accept anything the server sends for the unit tests
        }


        @Override
        public void headersContinue(int payloadSize, boolean endOfHeaders) {
            // NO-OP: Logging occurs per header
        }


        @Override
        public void headersEnd(int streamId) {
            trace.append(streamId + "-HeadersEnd\n");
        }


        @Override
        public void reset(int streamId, long errorCode) {
            trace.append(streamId + "-RST-[" + errorCode + "]\n");
        }


        @Override
        public void setting(Setting setting, long value) throws ConnectionException {
            trace.append("0-Settings-[" + setting + "]-[" + value + "]\n");
            remoteSettings.set(setting, value);
        }


        @Override
        public void settingsEnd(boolean ack) throws IOException {
            if (ack) {
                trace.append("0-Settings-Ack\n");
            } else {
                trace.append("0-Settings-End\n");
                sendSettings(0,  true);
            }
        }


        @Override
        public void pingReceive(byte[] payload, boolean ack) throws IOException {
            trace.append("0-Ping-");
            if (ack) {
                trace.append("Ack-");
            } else {
                sendPing(0, true, payload);
            }
            trace.append('[');
            boolean first = true;
            for (byte b : payload) {
                if (first) {
                    first = false;
                } else {
                    trace.append(',');
                }
                trace.append(b & 0xFF);
            }
            trace.append("]\n");
        }


        @Override
        public void goaway(int lastStreamId, long errorCode, String debugData) {
            trace.append("0-Goaway-[" + lastStreamId + "]-[" + errorCode + "]-[" + debugData + "]");
        }


        @Override
        public void incrementWindowSize(int streamId, int increment) {
            trace.append(streamId + "-WindowSize-[" + increment + "]\n");
        }


        @Override
        public void swallowed(int streamId, FrameType frameType, int flags, int size) {
            trace.append(streamId);
            trace.append(",");
            trace.append(frameType);
            trace.append(",");
            trace.append(flags);
            trace.append(",");
            trace.append(size);
            trace.append("\n");
        }


        @Override
        public void swallowedPadding(int streamId, int paddingLength) {
            trace.append(streamId);
            trace.append("-SwallowedPadding-[");
            trace.append(paddingLength);
            trace.append("]\n");
        }


        public void clearTrace() {
            trace = new StringBuffer();
            bytesRead = 0;
        }


        public String getTrace() {
            return trace.toString();
        }


        public int getMaxFrameSize() {
            return remoteSettings.getMaxFrameSize();
        }


        public long getBytesRead() {
            return bytesRead;
        }
    }


    private static class EmptyServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Generate an empty response
            resp.setContentType("application/octet-stream");
            resp.setContentLength(0);
            resp.flushBuffer();
        }
    }


    protected static class SimpleServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        public static final int CONTENT_LENGTH = 8192;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Generate content with a simple known format.
            resp.setContentType("application/octet-stream");

            int count = 4 * 1024;
            // Two bytes per entry
            resp.setContentLengthLong(count * 2);

            OutputStream os = resp.getOutputStream();
            byte[] data = new byte[2];
            for (int i = 0; i < count; i++) {
                data[0] = (byte) (i & 0xFF);
                data[1] = (byte) ((i >> 8) & 0xFF);
                os.write(data);
            }
        }


        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Do not do this at home. The unconstrained buffer is a DoS risk.

            // Have to read into a buffer because clients typically do not start
            // to read the response until the request is fully written.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOTools.flow(req.getInputStream(), baos);

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            IOTools.flow(bais, resp.getOutputStream());

            // Check for trailer headers
            String trailerValue = req.getHeader(TRAILER_HEADER_NAME);
            if (trailerValue != null) {
                resp.getOutputStream().write(trailerValue.getBytes(StandardCharsets.UTF_8));
            }
        }
    }


    private static class LargeServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Generate content with a simple known format that will exceed the
            // default flow control window for a stream.
            resp.setContentType("application/octet-stream");

            int count = 128 * 1024;
            // Two bytes per entry
            resp.setContentLengthLong(count * 2L);

            OutputStream os = resp.getOutputStream();
            byte[] data = new byte[2];
            for (int i = 0; i < count; i++) {
                data[0] = (byte) (i & 0xFF);
                data[1] = (byte) ((i >> 8) & 0xFF);
                os.write(data);
            }
        }
    }


    private static class CookieServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().print("Cookie count: " + req.getCookies().length);
            resp.flushBuffer();
        }
    }


    static class LargeHeaderServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            StringBuilder headerValue = new StringBuilder();
            Random random = new Random();
            while (headerValue.length() < 2048) {
                headerValue.append(Long.toString(random.nextLong()));
            }
            resp.setHeader(HEADER_IGNORED, headerValue.toString());
            resp.getWriter().print("OK");
        }
    }


    static class ParameterServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {

            Map<String,String[]> params = req.getParameterMap();

            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");

            resp.getWriter().print(params.size());
        }
    }


    static class SettingValue {
        private final int setting;
        private final long value;

        public SettingValue(int setting, long value) {
            this.setting = setting;
            this.value = value;
        }

        public int getSetting() {
            return setting;
        }

        public long getValue() {
            return value;
        }
    }


    static class Header {
        private final String name;
        private final String value;

        public Header(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }
    }
}

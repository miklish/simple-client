package com.networknt.clientmoduletest.com.networknt.simpleclient;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.balance.LoadBalance;
import com.networknt.client.Http2Client;
import com.networknt.cluster.Cluster;
import com.networknt.config.Config;
import com.networknt.exception.ApiException;
import com.networknt.exception.ClientException;
import com.networknt.service.SingletonServiceFactory;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SimpleHttp2Client2
{
    private Logger _logger = LoggerFactory.getLogger(SimpleHttp2Client2.class);
    private Http2Client _client;
    private String _responseBody = null;
    private ClientResponse _response = null;

    public static Methods METHODS;
    public static Headers HEADERS;

    private HttpServerExchange _exchange = null;
    private boolean _propagateToken = false;
    private boolean _addToken = false;
    private boolean _disableToken = false;
    private boolean _hasConnected = false;
    private String _url;
    private boolean _enableHttp2;
    private int _timeoutMs;
    private HttpString _scheme = Methods.GET;

    private String _serviceProtocol = "http";
    private String _serviceId = "";
    private String _serviceTag = null;
    private String _serviceRequestKey = null;

    private boolean _headersUpdated = false;

    private HeaderMap _hMap = new HeaderMap();

    /*
    private static final String SECURITY_CONFIG_NAME = "security";
    private static final Map<String, Object> _securityConfig =
        Config.getInstance().getJsonMapConfig(SECURITY_CONFIG_NAME);
    */
    private int _statusCode = 0;

    private static boolean _loadBalancerAvailable = false;
    private static boolean _clusterAvailable = false;
    private static Cluster _cluster;
    static {
        // Check whether
        _loadBalancerAvailable = SingletonServiceFactory.getBean(LoadBalance.class) != null;
        _clusterAvailable = (_cluster = SingletonServiceFactory.getBean(Cluster.class)) != null;
    }
    private static final String _errLoadBalancer =
        "\t* You need to configure load balancing algorithm in service.yml.\n" +
        "\t  For example, add the following to service.yml under the 'singletons' element:\n" +
        "\n" +
        "\t  - com.networknt.balance.LoadBalance:\n" +
        "\t    - com.networknt.balance.RoundRobinLoadBalance\n" +
        "\n";

    private static final String _errCluster =
        "\t* You need to configure a Cluster implementation in service.yml\n" +
        "\t  For example, add the following to service.yml under the 'singletons' element:\n" +
        "\n" +
        "\t  - com.networknt.cluster.Cluster:\n" +
        "\t    - com.networknt.cluster.LightCluster\n" +
        "\n";

    private static final String _errSecurity =
        "Unable to propagate token. You must either:\n" +
        "\t(1) use SimpleHttp2Client.propagateToken(HttpServerExchange) to enable propagation, or\n" +
        "\t(2) use SimpleHttp2Client.addToken() to acquire a new token based on configuration in client.yml\n" +
        "\t(3) use SimpleHttp2Client.disableToken() to disable both token propagation and token creation\n";

    public SimpleHttp2Client2() {
        _client = Http2Client.getInstance();
    }

    public SimpleHttp2Client2 connect()
        throws
            java.net.URISyntaxException,
            IOException,
            com.networknt.exception.ApiException,
            com.networknt.exception.ClientException
    {
        _hasConnected = false;
        URI uri = new URI(_url);
        ClientConnection connection =
            _client.connect(
                uri,
                Http2Client.WORKER,
                Http2Client.SSL,
                Http2Client.BUFFER_POOL,
                _enableHttp2 ?
                    OptionMap.create(UndertowOptions.ENABLE_HTTP2, _enableHttp2) :
                    OptionMap.EMPTY
            ).get();

        executeRequest(uri, connection);
        executeRequest(uri, connection);
        return this;
    }

    private void executeRequest(URI uri, ClientConnection connection)
            throws ClientException, ApiException
    {
        // Get a reference to the response
        final AtomicReference<ClientResponse> reference = new AtomicReference<>();
        // We only expect one response, so close the latch after it's received.
        final CountDownLatch latch = new CountDownLatch(1);

        // Build the request object:
        if(_logger.isDebugEnabled()) _logger.info("Contacting {}", _url);

        String path = (path = uri.getPath()) == null || path.isEmpty() ? "" : path;
        String query = (query = uri.getQuery()) == null || query.isEmpty() ? "" : "?" + query;
        ClientRequest request = new ClientRequest()
                .setPath(path + query)
                .setMethod(_scheme);

        if(_logger.isDebugEnabled()) _logger.info("Peer Address: {}", connection.getPeerAddress());

        addHeader(Headers.HOST, uri.getHost() + ":" + uri.getPort());
        updateHttpHeaders(request.getRequestHeaders());

        if(!_disableToken)
            if(_addToken)
                _client.addCcToken(request);
            else {
                if(_exchange == null) {
                    if(_logger.isDebugEnabled())
                        _logger.warn("\n\n* WARNING * " + _errSecurity);
                }
                else
                    _client.propagateHeaders(request, _exchange);
            }

        // Send the request
        connection.sendRequest(request, _client.createClientCallback(reference, latch));

        // Wait for the response, SLA is 100 milliseconds for example.
        try {
            if (_timeoutMs > 0)
                _hasConnected = latch.await(_timeoutMs, TimeUnit.MILLISECONDS);
            else {
                latch.await();
                _hasConnected = true;
            }
        } catch(InterruptedException e) {
            String err = "Thread interrupted while waiting for response:\n" + e.getMessage();
            if (!_logger.isDebugEnabled()) _logger.error(err);
            throw new RuntimeException(err);
        }

        // Read the response.
        _response = reference.get();

        if(_response == null) {
            if(_logger.isDebugEnabled()) _logger.info("No response");
            return;
        }

        _statusCode = _response.getResponseCode();
        if(_logger.isDebugEnabled()) _logger.info("Status Code: {}", _statusCode);
        _responseBody = _response.getAttachment(Http2Client.RESPONSE_BODY);
    }

    public Object toObject(Class respClass) throws
        JsonMappingException, JsonParseException, IOException
    {
        if(_hasConnected || _responseBody != null) {
            return getMapper().readValue(getResponseBody(), respClass);
        }
        else {
            if(_logger.isDebugEnabled()) {
                if(!_hasConnected) _logger.error("Cannot return response object as no connection was made");
                if(_responseBody  == null) _logger.error("Cannot return response object as response body was empty");
            }
            return null;
        }
    }

    public Map<String, Object> toMap() throws
        JsonMappingException, JsonParseException, IOException
    {
        if(_hasConnected || _responseBody != null) {
            return getMapper().readValue(
                       getResponseBody(),
                        new TypeReference<Map<String,Object>>(){}
                   );
        }
        else {
            if (_logger.isDebugEnabled()) {
                if (!_hasConnected) _logger.error("Cannot return response map as no connection was made");
                if (_responseBody == null) _logger.error("Cannot return response map as response body was empty");
            }
            return null;
        }
    }

    public SimpleHttp2Client2 setServiceProtocol(String serviceProtocol) {
        _serviceProtocol = serviceProtocol;
        return this;
    }

    public SimpleHttp2Client2 setSericeId(String serviceId) {
        _serviceId = serviceId;
        return this;
    }

    public SimpleHttp2Client2 setServiceTag(String serviceTag) {
        _serviceTag = serviceTag;
        return this;
    }

    public SimpleHttp2Client2 setServiceRequestKey(String serviceRequestKey) {
        _serviceRequestKey = serviceRequestKey;
        return this;
    }

    public ObjectMapper getMapper() {
        return Config.getInstance().getMapper();
    }

    public ClientResponse getResponse() {
        return _response;
    }

    public String getResponseBody() {
        return _responseBody;
    }

    public boolean hasConnected() {
        return _hasConnected;
    }

    public int getStatusCode() {
        if(_response == null)
            return StatusCodes.GATEWAY_TIME_OUT;
        else
            return _response.getResponseCode();
    }

    public String getStatusMessage() {
        return StatusCodes.getReason(getStatusCode());
    }

    public SimpleHttp2Client2 disableToken() {
        _disableToken = true;
        return this;
    }

    public SimpleHttp2Client2 addToken() {
        _addToken = true;
        _propagateToken = false;
        return this;
    }

    public SimpleHttp2Client2 propagateToken(HttpServerExchange exchange) {
        _exchange = exchange;
        _propagateToken = true;
        _addToken = false;
        return this;
    }

    public SimpleHttp2Client2 setTimeoutMs(int timeoutMs) {
        _timeoutMs = timeoutMs;
        return this;
    }

    public SimpleHttp2Client2 setUrl(String url) {
        _url = url;
        return this;
    }

    public SimpleHttp2Client2 enableHttp2() {
        _enableHttp2 = true;
        return this;
    }

    public SimpleHttp2Client2 lookupConnect(String path)
            throws Exception
    {
        if(!_loadBalancerAvailable || !_clusterAvailable) {
            String err =
                "\n\n" +
                (!_loadBalancerAvailable ? _errLoadBalancer : "") +
                (!_clusterAvailable ? _errCluster : "");
            if(_logger.isDebugEnabled()) _logger.error(err);
            throw new ExceptionInInitializerError(err);
        }

        setUrl(_cluster.serviceToUrl(_serviceProtocol, _serviceId, _serviceTag, _serviceRequestKey) + path);
        return connect();
    }

    public SimpleHttp2Client2 get() {
        _scheme = Methods.GET;
        return this;
    }
    public SimpleHttp2Client2 put() {
        _scheme = Methods.PUT;
        return this;
    }
    public SimpleHttp2Client2 post() {
        _scheme = Methods.POST;
        return this;
    }
    public SimpleHttp2Client2 delete() {
        _scheme = Methods.DELETE;
        return this;
    }

    public void addHeader(String header, String value) {
        addHeader(header != null ? new HttpString(header) : null, value);
    }

    public void addHeader(HttpString header, String value) {
        if(header != null && header.length() != 0 && value != null) {
            _headersUpdated = true;
            _hMap.add(header, value);
        }
        else
            if(_logger.isDebugEnabled())
                _logger.info("Could not add HEADER [{}] : [{}]",
                        header == null ? "null" : header.toString(),
                        value == null ? "null" : value);
    }

    private void updateHttpHeaders(HeaderMap realMap) {
        for(HttpString field : _hMap.getHeaderNames()) {
            HeaderValues rValues = realMap.get(field);

            if(!realMap.contains(field))
                realMap.addAll(field, rValues);
            else {
                HeaderValues hValues = _hMap.get(field);
                for(int i = 0; i < hValues.size(); ++i)
                    if(!rValues.contains(hValues.get(i)))
                        realMap.add(field, hValues.get(i));
            }
        }
    }
}

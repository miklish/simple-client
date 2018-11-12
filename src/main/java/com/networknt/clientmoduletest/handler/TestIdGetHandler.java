
package com.networknt.clientmoduletest.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.client.Http2Client;
import com.networknt.clientmoduletest.com.networknt.simpleclient.SimpleHttp2Client;
import com.networknt.clientmoduletest.model.User;
import com.networknt.cluster.Cluster;
import com.networknt.config.Config;
import com.networknt.handler.LightHttpHandler;
import com.networknt.security.JwtHelper;
import com.networknt.service.SingletonServiceFactory;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.xnio.OptionMap;

import java.net.URI;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class TestIdGetHandler implements LightHttpHandler
{
    private static final String SECURITY_CONFIG_NAME = "security";
    private static final Map<String, Object> _securityConfig =
            Config.getInstance().getJsonMapConfig(SECURITY_CONFIG_NAME);

    //@Override
    public void handleRequest2(HttpServerExchange exchange) throws Exception
    {
        Map<String, Deque<String>> parms = exchange.getQueryParameters();
        String id = parms.get("id").getFirst();
        logger.info("Input id :: {}", id);

        Cluster cluster = SingletonServiceFactory.getBean(Cluster.class);

        // protocol, serviceId, environment, requestKey (load balancer key, not required in most cases)
        String apiHost = cluster.serviceToUrl("https", "free-api", null, null);
        logger.info("URL of service :: {}", apiHost);

        URI uri = new URI(apiHost);
        Http2Client client = Http2Client.getInstance();
        boolean enableHttp2 = false;
        ClientConnection connection =
            client.connect(
                new URI(apiHost),
                Http2Client.WORKER,
                Http2Client.SSL,
                Http2Client.BUFFER_POOL,
                enableHttp2 ?
                    OptionMap.create(UndertowOptions.ENABLE_HTTP2, true) :
                    OptionMap.EMPTY
            ).get();

        // Get a reference to the response
        final AtomicReference<ClientResponse> reference = new AtomicReference<>();
        // We only expect one response, so close the latch after it's received.
        final CountDownLatch latch = new CountDownLatch(1);

        // Build the request object:
        ClientRequest request = new ClientRequest().setPath("/todos/" + id).setMethod(Methods.GET);

        logger.info("Connection to: {}", connection.getPeerAddress());

        request.getRequestHeaders().add(Headers.HOST, uri.getHost() + ":" + uri.getPort());
        logger.info("REQUEST: " + request.toString());
        logger.info("HEADERS: " + request.getRequestHeaders().toString());

        // Security
        boolean securityEnabled = false;
        boolean propagateToken = false;  // set propagateToken to true or false as needed

        if(_securityConfig != null)
            securityEnabled = (Boolean) _securityConfig.get(JwtHelper.ENABLE_VERIFY_JWT);

        if (securityEnabled)
            if(propagateToken)
                client.addCcToken(request);     // configure OAuth in client.yml
            else
                client.propagateHeaders(request, exchange);

        // Send the request
        connection.sendRequest(request, client.createClientCallback(reference, latch));

        // Wait for the response, SLA is 100 milliseconds for example.
        //latch.await(10000, TimeUnit.MILLISECONDS);
        latch.await();
        // Read the response.
        ClientResponse response = reference.get();

        int statusCode = response.getResponseCode();
        logger.info("Status Code: {}", statusCode);
        String responseBody = response.getAttachment(Http2Client.RESPONSE_BODY);

        logger.info(responseBody);

        // Load data into User object
        ObjectMapper objectMapper = Config.getInstance().getMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        User user = objectMapper.readValue(responseBody, User.class);
        logger.info("user.getTitle() = {}", user.getTitle());

        // Or read as a Map
        Map<String, Object> userMap =
            objectMapper.readValue(responseBody, new TypeReference<Map<String,Object>>(){});
        logger.info("userMap.get(\"title\").toString() = {}", userMap.get("title").toString());

        // See https://www.baeldung.com/jackson-object-mapper-tutorial for more
        // info on using ObjectMapper

        exchange.endExchange();
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception
    {
        Map<String, Deque<String>> parms = exchange.getQueryParameters();
        String id = parms.get("id").getFirst();

        // Direct connection
        SimpleHttp2Client client1 = new SimpleHttp2Client()
            .enableHttp2()
            .setTimeoutMs(10000)
            .setUrl("https://jsonplaceholder.typicode.com/todos/" + id)
            .connect();

        // Connection via Service Registry
        SimpleHttp2Client client = new SimpleHttp2Client()
            .enableHttp2()
            .setTimeoutMs(10000)
            .setSericeId("free-api")
            .setServiceProtocol("https")
            .get()
            .lookupConnect("/todos/" + id);

        logger.info("Response: \n{}", client.hasConnected() ? client.getResponseBody() : "Did not connect");

        if(!client.hasConnected()) {
            logger.info("No response");
            return;
        }

        logger.info("HTTP response: {}", client.getStatusMessage());
        if(client.getStatusCode() != 200)
            return;

        // Load data into User object
        User user = (User) client.toObject(User.class);

        logger.info("user.getTitle() = {}", user.getTitle());

        // Or read as a Map
        Map<String, Object> userMap = client.toMap();

        logger.info("userMap.get(title) = {}", userMap.get("title").toString());

        exchange.endExchange();
    }
}

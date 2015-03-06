/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.corfudb.infrastructure;

import org.corfudb.client.CorfuDBView;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.thrift.TException;

import org.slf4j.MarkerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;

import javax.json.JsonWriter;
import javax.json.Json;
import javax.json.JsonValue;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonArray;

import java.util.UUID;

public class ConfigMasterServer implements Runnable, ICorfuDBServer {

    private Logger log = LoggerFactory.getLogger(ConfigMasterServer.class);
    private Map<String,Object> config;
    private CorfuDBView currentView;
    private Boolean viewActive;

    int masterid = new SecureRandom().nextInt();

    public ConfigMasterServer() {
    }

    public Runnable getInstance(final Map<String,Object> config)
    {
        //use the config for the init view (well, we'll have to deal with reconfigurations...)
        this.config = config;
        viewActive = false;
        currentView = new CorfuDBView(config);
        return this;
    }

    public void checkViewThread() {
        log.info("Starting view check thread");
        while(true)
        {
            try {
                boolean success = currentView.isViewAccessible();
                if (success && !viewActive)
                {
                    log.info("New view is now accessible and active");
                    currentView.setEpoch(0);
                    viewActive = true;
                    synchronized(viewActive)
                    {
                        viewActive.notify();
                    }
                }
                else if(!success)
                {
                    log.info("View is not accessible, checking again in 30s");
                }
                synchronized(viewActive)
                {
                    viewActive.wait(30000);
                }
            }
            catch (InterruptedException ie)
            {

            }
        }
    }

    public void run()
    {
        try {
            log.info("Starting HTTP Service on port " + config.get("port"));
            HttpServer server = HttpServer.create(new InetSocketAddress((Integer)config.get("port")), 0);
            server.createContext("/corfu", new RequestHandler());
            server.createContext("/control", new ControlRequestHandler());
            server.setExecutor(null);
            server.start();
            checkViewThread();
        } catch(IOException ie) {
            log.error(MarkerFactory.getMarker("FATAL"), "Couldn't start HTTP Service!" , ie);
            System.exit(1);
        }
    }

    private void reset() {
        log.info("RESET requested, resetting all nodes and incrementing epoch");
    }

    private JsonValue addStream(JsonArray params)
    {
        try {
            JsonObject jo = params.getJsonObject(0);
            currentView.updateStream(UUID.fromString(jo.getJsonString("uuid").getString()), jo.getJsonNumber("start").longValue());
        }
        catch (Exception ex)
        {
            log.error("Error adding stream", ex);
            return JsonValue.FALSE;
        }
        return JsonValue.TRUE;
    }

    private class ControlRequestHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {

            String response = null;

            if (t.getRequestMethod().startsWith("POST")) {
                log.debug("POST request:" + t.getRequestURI());
                String apiCall = null;
                JsonArray params = null;
                try (InputStreamReader isr  = new InputStreamReader(t.getRequestBody(), "utf-8"))
                {
                    try (BufferedReader br = new BufferedReader(isr))
                    {
                        try (JsonReader jr = Json.createReader(br))
                        {
                            JsonObject jo  = jr.readObject();
                            apiCall = jo.getString("method");
                            params = jo.getJsonArray("params");
                        }
                        catch (Exception e)
                        {
                            log.error("error", e);
                        }
                    }
                }
                JsonValue result = JsonValue.FALSE;
                switch(apiCall)
                {
                    case "reset":
                        reset();
                        result = JsonValue.TRUE;
                        break;
                    case "addstream":
                        result = addStream(params);
                        break;

                }
                JsonObject res = Json.createObjectBuilder()
                                    .add("calledmethod", apiCall)
                                    .add("result", result)
                                    .build();
                response = res.toString();
            } else {
                log.debug("PUT request");
                response = "deny";
            }
                t.sendResponseHeaders(200, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();

        }
    }

    private class RequestHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {

            String response = null;

            if (t.getRequestMethod().startsWith("GET")) {
                log.debug("GET request:" + t.getRequestURI());
                StringWriter sw = new StringWriter();
                try (JsonWriter jw = Json.createWriter(sw))
                {
                    jw.writeObject(currentView.getSerializedJSONView());
                }
                response = sw.toString();
                log.debug("Response is", response);
            } else {
                log.debug("PUT request");
                response = "deny";
            }
                t.sendResponseHeaders(200, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();

        }
    }

}


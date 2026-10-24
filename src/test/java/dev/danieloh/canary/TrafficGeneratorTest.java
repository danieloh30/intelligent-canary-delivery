package dev.danieloh.canary;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TrafficGeneratorTest {
    @Test void usesFreshConnectionsSoNewCanaryPodsReceiveTraffic() throws Exception {
        AtomicInteger posts=new AtomicInteger(),closed=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/checkout",exchange->{
            if(exchange.getRequestMethod().equals("POST"))posts.incrementAndGet();
            if("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection")))closed.incrementAndGet();
            exchange.sendResponseHeaders(200,0);exchange.close();
        });
        server.start();
        try {
            var traffic=new TrafficGenerator();traffic.role="traffic";traffic.url="http://127.0.0.1:"+server.getAddress().getPort();
            traffic.generate();assertEquals(30,posts.get());assertEquals(30,closed.get());
        } finally {server.stop(0);}
    }
}

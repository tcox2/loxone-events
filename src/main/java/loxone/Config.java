package loxone;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

record Config(URI endpoint,String username,String password,String jdbcUrl,String databaseUser,String databasePassword,Path stateDirectory) {
    static final ObjectMapper JSON=new ObjectMapper();
    static Config read(Path path)throws Exception {
        var n=JSON.readTree(Files.readAllBytes(path));
        var uri=URI.create(text(n,"endpoint"));
        Events.require("wss".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null,
                "endpoint must be a WSS URL with a valid trusted certificate");
        String jdbc=text(n,"jdbc_url");Events.require(jdbc.startsWith("jdbc:postgresql:")&&jdbc.contains("/loxone"),"JDBC URL must address PostgreSQL database loxone");
        return new Config(uri,text(n,"username"),text(n,"password"),jdbc,text(n,"database_user"),text(n,"database_password"),Path.of(text(n,"state_directory")));
    }
    static String text(JsonNode n,String name){var v=n.get(name);Events.require(v!=null&&v.isTextual()&&!v.asText().isBlank(),"Missing configuration field: "+name);return v.asText();}
    @Override public String toString(){return "Config[credentials redacted]";}
}

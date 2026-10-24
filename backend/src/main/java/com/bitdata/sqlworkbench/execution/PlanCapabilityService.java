package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.PlanCapabilities;
import com.bitdata.sqlworkbench.execution.model.PlanCapabilities.ModeCapability;
import org.springframework.stereotype.Component;
import java.sql.Connection;

@Component
public class PlanCapabilityService {
    private final ConnectionService connections;
    private final ExplainServiceFactory adapters;
    public PlanCapabilityService(ConnectionService connections,ExplainServiceFactory adapters) { this.connections=connections; this.adapters=adapters; }
    public PlanCapabilities get(String id) {
        String dialect=connections.getById(id).orElseThrow().dialect();
        try(Connection connection=connections.openJdbcConnection(id)) { return inspect(id,dialect,connection,adapters); }
        catch(CredentialFailure e) { throw e; }
        catch(Exception e) { return unknown(id,dialect,"CAPABILITY_PROBE_FAILED"); }
    }
    static PlanCapabilities unknown(String id,String dialect,String reason) {
        return new PlanCapabilities(id,dialect,null,null,new ModeCapability("UNKNOWN",reason),new ModeCapability("UNKNOWN",reason),null,true,reason);
    }
    static PlanCapabilities inspect(String id,String dialect,Connection connection,ExplainServiceFactory adapters) throws java.sql.SQLException {
        var metadata=connection.getMetaData();
        String product=metadata.getDatabaseProductName(), version=metadata.getDatabaseProductVersion();
        if(product==null || version==null || version.isBlank()) return unknown(id,dialect,"SERVER_METADATA_UNVERIFIED");
        boolean pg="postgresql".equals(dialect) && product.equalsIgnoreCase("PostgreSQL");
        boolean mysql="mysql".equals(dialect) && product.equalsIgnoreCase("MySQL");
        boolean supported=(pg || mysql) && adapters.supports(dialect);
        var estimated=new ModeCapability(supported?"SUPPORTED":"UNSUPPORTED",supported?null:"APPLICATION_CHANNEL_UNSUPPORTED");
        var actual=new ModeCapability(supported && pg?"SUPPORTED":"UNSUPPORTED",supported && pg?null:"APPLICATION_CHANNEL_UNSUPPORTED");
        return new PlanCapabilities(id,dialect,product,version,estimated,actual,java.time.Instant.now().toString(),false,null);
    }
}

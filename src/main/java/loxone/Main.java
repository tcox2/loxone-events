package loxone;

import java.nio.file.*;
import java.time.*;

public final class Main {
    static void log(String message){System.out.println(Instant.now()+" "+message);}
    public static void main(String[] args)throws Exception {
        Events.require(args.length==1,"Usage: java -jar loxone-events.jar /path/to/config.json");
        var config=Config.read(Path.of(args[0]));
        try(var spool=new Spool(config.stateDirectory())) {
            Thread writer=Thread.ofPlatform().name("postgres-writer").start(()->{
                while(!Thread.currentThread().isInterrupted()) {
                    try(var db=spool.connect(config)) {
                        while(!Thread.currentThread().isInterrupted()) {
                            int count=spool.drain(db);if(count>0)log("stored events="+count);else Thread.sleep(500);
                        }
                    }catch(InterruptedException e){Thread.currentThread().interrupt();}
                    catch(Exception e){log("postgres_write_failed type="+e.getClass().getSimpleName()+(e instanceof java.sql.SQLException sql?" sqlstate="+sql.getSQLState():"")+"; disk buffer retained");try{Thread.sleep(5000);}catch(InterruptedException stop){Thread.currentThread().interrupt();}}
                }
            });
            var main=Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(()->{main.interrupt();writer.interrupt();}));
            int delay=5;
            while(!Thread.currentThread().isInterrupted()) {
                long started=System.nanoTime();
                try(var collector=new Collector(config,spool)){collector.run();}
                catch(Exception e){
                    if(Thread.currentThread().isInterrupted()||e instanceof InterruptedException)break;
                    // Never log full exceptions: protocol responses/URLs can contain authentication material.
                    Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
                    String detail=cause.getMessage()!=null&&cause.getMessage().startsWith("Miniserver ")?" detail="+cause.getMessage():"";
                    log("connection_failed type="+cause.getClass().getSimpleName()+detail+"; reconnecting in "+delay+" seconds");
                    Thread.sleep(delay*1000L);
                    delay=System.nanoTime()-started>Duration.ofMinutes(2).toNanos()?5:Math.min(delay*2,300);
                }
            }
            writer.interrupt();writer.join(35000);
        }
    }
}

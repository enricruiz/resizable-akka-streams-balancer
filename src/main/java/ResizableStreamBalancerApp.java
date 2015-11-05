import static java.util.UUID.randomUUID;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

public class ResizableStreamBalancerApp
{
    public static void main(String[] args)
    {
        final ActorSystem system = ActorSystem.create("resizable-streams-example");
        final ActorMaterializer materializer = ActorMaterializer.create(system);

        Stream<Messages.Email> emails = Stream.generate(new Supplier<Messages.Email>()
        {
            @Override
            public Messages.Email get()
            {
                return new Messages.Email(randomUUID().toString(), randomUUID().toString());
            }
        });

        ActorRef actor = Source.from(emails::iterator) //
            .runWith(Sink.actorSubscriber(MailMaster.mkProps(materializer)), materializer);

        system.actorOf(MailWorker.mkProps(actor));

        system.scheduler().schedule( //
            Duration.create(2, TimeUnit.SECONDS), //
            Duration.create(2, TimeUnit.SECONDS), //
            () -> system.actorOf(MailWorker.mkProps(actor)), //
            system.dispatcher());
    }
}

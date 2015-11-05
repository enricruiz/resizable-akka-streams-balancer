import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.concurrent.duration.Duration;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;

public class MailWorker extends AbstractActor
{
    private Logger log = LoggerFactory.getLogger(MailWorker.class);

    public static Props mkProps(final ActorRef master)
    {
        return Props.create(MailWorker.class, master);
    }

    public MailWorker(final ActorRef master)
    {
        master.tell(new Messages.MailSenderRegistration(), self());

        receive(ReceiveBuilder.match(Messages.SendEmailJob.class, emailJob -> {
            ActorRef replyTo = sender();
            log.info("Sent email to {}", emailJob.email.address);
            context().system().scheduler().scheduleOnce( //
                Duration.create(250, TimeUnit.MILLISECONDS), //
                replyTo, //
                new Messages.EmailSent(emailJob.id), //
                context().dispatcher(), //
                self());
        }).build());
    }
}

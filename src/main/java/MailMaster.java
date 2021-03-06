import static akka.japi.pf.ReceiveBuilder.match;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import scala.PartialFunction;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.dispatch.Mapper;
import akka.dispatch.Recover;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Patterns;
import akka.stream.FlowShape;
import akka.stream.Graph;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.UniformFanInShape;
import akka.stream.UniformFanOutShape;
import akka.stream.actor.AbstractActorSubscriber;
import akka.stream.actor.ActorSubscriberMessage.OnNext;
import akka.stream.actor.RequestStrategy;
import akka.stream.javadsl.Balance;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.FlowGraph;
import akka.stream.javadsl.Merge;
import akka.stream.javadsl.RunnableGraph;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.Timeout;

public class MailMaster extends AbstractActorSubscriber
{
    public static Props mkProps(final Materializer materializer)
    {
        return Props.create(MailMaster.class, materializer);
    }

    private int workersCount = 0;

    private int parallelism = 1;

    private Map<Integer, Messages.SendEmailJob> pending = new HashMap<>();

    private Map<Integer, Messages.SendEmailJob> started = new HashMap<>();

    private int currentEmailId = 0;

    private Materializer materializer;

    private int maxQueueSize()
    {
        return workersCount * parallelism;
    }

    public MailMaster(final Materializer materializer)
    {
        this.materializer = materializer;
        receive(noWorkers);
    }

    private int getNewId()
    {
        int current = currentEmailId;
        currentEmailId += 1;
        return current;
    }

    private PartialFunction<Object, BoxedUnit> noWorkers = ReceiveBuilder //
        .match(OnNext.class, n -> {
            int emailJobId = getNewId();
            Messages.SendEmailJob job = new Messages.SendEmailJob(emailJobId, //
                (Messages.Email) n.element());
            pending.put(emailJobId, job);
            System.out.println("New job enqueud: " + emailJobId);
        })

        .match(Messages.MailSenderRegistration.class, r -> {
            ActorRef newWorker = getContext().watch(sender());
            Set<ActorRef> workers = new HashSet<>();
            workers.add(newWorker);
            context().become(hasWorkers(workers, createBalancer(workers).run(materializer)));
            workersCount = 1;
        }).build();

    private PartialFunction<Object, BoxedUnit> hasWorkers(final Set<ActorRef> workers,
        ActorRef balancer)
    {
        return match(Terminated.class, a -> {
            workers.remove(a);
            updateWorkers(workers, balancer);
        })

        .match(Messages.MailSenderRegistration.class, a -> {
            workers.add(context().watch(sender()));
            updateWorkers(workers, balancer);
        })

        .match(OnNext.class, e -> {
            int emailJobId = getNewId();
            Messages.SendEmailJob job = new Messages.SendEmailJob(emailJobId, //
                (Messages.Email) e.element());
            started.put(emailJobId, job);
            balancer.tell(job, context().self());
        })

        .match(Messages.EmailSent.class, e -> {
            System.out.println("Done and removed!");
            started.remove(e.id);
        })

        .match(Messages.EmailFailed.class, e -> {
            System.out.println("Done and failed");
            started.remove(e.id);
        }).build();
    }

    @Override
    public RequestStrategy requestStrategy()
    {
        return new DynamicMaxInFlightRequestStrategy(maxQueueSize(), started.size()
            + pending.size());
    }

    private Flow<Messages.SendEmailJob, Messages.DeliveryStatus, BoxedUnit> sendToWorker(
        final ActorRef worker)
    {
        final Timeout timeout = new Timeout(Duration.create(2, TimeUnit.SECONDS));
        final ExecutionContextExecutor ec = context().dispatcher();

        return Flow.of(Messages.SendEmailJob.class).mapAsyncUnordered(parallelism, emailJob -> {
            return Patterns.ask(worker, emailJob, timeout) //
                .map(new Mapper<Object, Messages.DeliveryStatus>()
                {
                    @Override
                    public Messages.EmailSent apply(Object object)
                    {
                        return (Messages.EmailSent) object;
                    }
                }, ec) //
                .recover(new Recover<Messages.DeliveryStatus>()
                {
                    @Override
                    public Messages.EmailFailed recover(Throwable problem) throws Throwable
                    {
                        return new Messages.EmailFailed(emailJob.id);
                    }
                }, ec);
        });
    }

    private RunnableGraph<ActorRef> createBalancer(Set<ActorRef> workers)
    {
        Graph<FlowShape<Messages.SendEmailJob, Messages.DeliveryStatus>, BoxedUnit> flow =
            FlowGraph.create(b -> {
                UniformFanOutShape<Messages.SendEmailJob, Messages.SendEmailJob> balance =
                    b.add(Balance.create(workers.size()));

                UniformFanInShape<Messages.DeliveryStatus, Messages.DeliveryStatus> merge =
                    b.add(Merge.create(workers.size()));

                workers.forEach(worker -> b.from(balance) //
                    .via(b.add(sendToWorker(worker))) //
                    .toFanIn(merge));

                return FlowShape.of(balance.in(), merge.out());
            });

        Source<Messages.SendEmailJob, ActorRef> source = Source.actorRef(2 * maxQueueSize() //
            , OverflowStrategy.fail());

        return source.via(flow).to(Sink.actorRef(self(), Completed.INTANCE));
    }

    private enum Completed
    {
        INTANCE
    }

    private void updateWorkers(Set<ActorRef> newWorkers, ActorRef oldBalancer)
    {
        ExecutionContext ec = getContext().dispatcher();
        context()
            .system()
            .scheduler()
            .scheduleOnce(Duration.create(2, TimeUnit.SECONDS), oldBalancer,
                PoisonPill.getInstance(), ec, self());

        workersCount = newWorkers.size();
        if (workersCount > 0)
        {
            ActorRef newBalancer = createBalancer(newWorkers).run(materializer);
            context().become(hasWorkers(newWorkers, newBalancer));

            if (!pending.isEmpty())
            {
                // Start all pending jobs
                started.putAll(pending);
                pending.clear();
                pending.forEach((uuid, job) -> newBalancer.tell(job, self()));
            }
        }
        else
        {
            context().become(noWorkers);
        }
    }
}

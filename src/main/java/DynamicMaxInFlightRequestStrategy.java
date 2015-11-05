import akka.stream.actor.RequestStrategy;

public class DynamicMaxInFlightRequestStrategy implements RequestStrategy
{
    public int max;

    public int inFlightInternally;

    public int batchSize = 5;

    public DynamicMaxInFlightRequestStrategy(int max, int inFlightInternally)
    {
        this.max = max;
        this.inFlightInternally = inFlightInternally;
    }

    @Override
    public int requestDemand(int remainingRequested)
    {
        int batch = Math.min(batchSize, max);
        if (remainingRequested + inFlightInternally <= max - batch)
        {
            return Math.max(0, max - remainingRequested - inFlightInternally);
        }
        return 0;
    }
}

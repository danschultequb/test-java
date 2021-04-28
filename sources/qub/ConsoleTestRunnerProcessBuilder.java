package qub;

public class ConsoleTestRunnerProcessBuilder extends JavaProcessBuilderDecorator<ConsoleTestRunnerProcessBuilder> implements ConsoleTestRunnerArguments<ConsoleTestRunnerProcessBuilder>
{
    private ConsoleTestRunnerProcessBuilder(JavaProcessBuilder javaProcessBuilder)
    {
        super(javaProcessBuilder);
    }

    /**
     * Get a JavaProcessBuilder from the provided Process.
     * @param process The Process to get the JavaProcessBuilder from.
     * @return The JavaProcessBuilder.
     */
    public static Result<ConsoleTestRunnerProcessBuilder> create(RealDesktopProcess process)
    {
        PreCondition.assertNotNull(process, "process");

        return ConsoleTestRunnerProcessBuilder.create(process.getProcessFactory());
    }

    /**
     * Get a JavaProcessBuilder from the provided ProcessFactory.
     * @param processFactory The ProcessFactory to get the JavaProcessBuilder from.
     * @return The JavaProcessBuilder.
     */
    public static Result<ConsoleTestRunnerProcessBuilder> create(ProcessFactory processFactory)
    {
        PreCondition.assertNotNull(processFactory, "processFactory");

        return Result.create(() ->
        {
            return new ConsoleTestRunnerProcessBuilder(JavaProcessBuilder.create(processFactory).await());
        });
    }
}

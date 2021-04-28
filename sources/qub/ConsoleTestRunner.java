package qub;

/**
 * A Console object that is used for running unit tests for other applications.
 */
public class ConsoleTestRunner implements TestRunner
{
    public static void main(String[] args)
    {
        DesktopProcess.run(args, ConsoleTestRunner::getParameters, ConsoleTestRunner::run);
    }

    public static ConsoleTestRunnerParameters getParameters(DesktopProcess process)
    {
        PreCondition.assertNotNull(process, "process");

        final CommandLineParameters parameters = process.createCommandLineParameters();
        final CommandLineParameter<PathPattern> patternParameter = parameters.add("pattern", (String argumentValue) ->
        {
            return Result.success(Strings.isNullOrEmpty(argumentValue)
                ? null
                : PathPattern.parse(argumentValue));
        });
        final CommandLineParameter<Coverage> coverageParameter = parameters.addEnum("coverage", Coverage.None, Coverage.Sources);
        final CommandLineParameter<Folder> outputFolderParameter = parameters.addFolder("output-folder", process);
        final CommandLineParameterVerbose verboseParameter = parameters.addVerbose(process);
        final CommandLineParameterProfiler profilerParameter = parameters.addProfiler(process, ConsoleTestRunner.class);
        final CommandLineParameterBoolean testJsonParameter = parameters.addBoolean("testjson", true);
        final CommandLineParameter<File> logFileParameter = parameters.addFile("logfile", process);
        final CommandLineParameterList<String> testClassNamesParameter = parameters.addPositionStringList("test-class");

        profilerParameter.await();

        final VerboseCharacterToByteWriteStream verbose = verboseParameter.getVerboseCharacterToByteWriteStream().await();
        final Folder outputFolder = outputFolderParameter.getValue().await();
        final Iterable<String> testClassNames = testClassNamesParameter.getValues().await();
        return new ConsoleTestRunnerParameters(process, verbose, outputFolder, testClassNames)
            .setPattern(patternParameter.getValue().await())
            .setCoverage(coverageParameter.getValue().await())
            .setTestJson(testJsonParameter.getValue().await())
            .setLogFile(logFileParameter.getValue().await());
    }

    public static int run(ConsoleTestRunnerParameters parameters)
    {
        PreCondition.assertNotNull(parameters, "parameters");

        final DesktopProcess process = parameters.getProcess();

        final Stopwatch stopwatch = process.getClock().createStopwatch();
        stopwatch.start();

        final PathPattern pattern = parameters.getPattern();
        final Folder outputFolder = parameters.getOutputFolder();
        final Iterable<String> testClassNames = parameters.getTestClassNames();
        final Boolean useTestJson = parameters.getTestJson();
        final File logFile = parameters.getLogFile();
        final Coverage coverage = parameters.getCoverage();

        final LogStreams logStreams;
        final CharacterToByteWriteStream output;
        final VerboseCharacterToByteWriteStream verbose;
        if (logFile == null)
        {
            logStreams = null;
            output = process.getOutputWriteStream();
            verbose = parameters.getVerbose();
        }
        else
        {
            logStreams = CommandLineLogsAction.getLogStreamsFromLogFile(logFile, process.getOutputWriteStream(), parameters.getVerbose());
            output = logStreams.getOutput();
            verbose = logStreams.getVerbose();
        }

        int result;
        try
        {
            final ConsoleTestRunner runner = new ConsoleTestRunner(process, output, pattern);

            final List<TestJSONClassFile> testJSONClassFiles = List.create();

            MutableMap<String, TestJSONClassFile> fullClassNameToTestJSONClassFileMap = Map.create();
            final VersionNumber currentJavaVersion = process.getJavaVersion();
            VersionNumber previousJavaVersion = null;
            if (useTestJson)
            {
                final TestJSON testJson = TestJSON.parse(outputFolder.getFile("test.json").await())
                    .catchError(FileNotFoundException.class)
                    .await();
                if (testJson != null)
                {
                    verbose.writeLine("Found and parsed test.json file.").await();
                    previousJavaVersion = testJson.getJavaVersion().catchError().await();
                    for (final TestJSONClassFile testJSONClassFile : testJson.getClassFiles())
                    {
                        fullClassNameToTestJSONClassFileMap.set(testJSONClassFile.getFullClassName(), testJSONClassFile);
                    }
                }

                runner.afterTestClass((TestClass testClass) ->
                {
                    verbose.writeLine("Updating test.json class file for " + testClass.getFullName() + "...").await();
                    final File testClassFile = QubTestRun.getClassFile(outputFolder, testClass.getFullName());
                    testJSONClassFiles.addAll(TestJSONClassFile.create(testClassFile.relativeTo(outputFolder))
                        .setLastModified(testClassFile.getLastModified().await())
                        .setPassedTestCount(testClass.getPassedTestCount())
                        .setSkippedTestCount(testClass.getSkippedTestCount())
                        .setFailedTestCount(testClass.getFailedTestCount()));
                });
            }

            for (final String testClassName : testClassNames)
            {
                boolean runTestClass;

                if (!useTestJson || !currentJavaVersion.equals(previousJavaVersion) || coverage != Coverage.None)
                {
                    runTestClass = true;
                }
                else
                {
                    final TestJSONClassFile testJSONClassFile = fullClassNameToTestJSONClassFileMap.get(testClassName)
                        .catchError(NotFoundException.class)
                        .await();
                    if (testJSONClassFile == null)
                    {
                        verbose.writeLine("Found class that didn't exist in previous test run: " + testClassName);
                        runTestClass = true;
                    }
                    else
                    {
                        verbose.writeLine("Found class entry for " + testClassName + ". Checking timestamps...").await();
                        final File testClassFile = outputFolder.getFile(testJSONClassFile.getRelativePath()).await();
                        final DateTime testClassFileLastModified = testClassFile.getLastModified().await();
                        if (!testClassFileLastModified.equals(testJSONClassFile.getLastModified()))
                        {
                            verbose.writeLine("Timestamp of " + testClassName + " from the previous run (" + testJSONClassFile.getLastModified() + ") was not the same as the current class file timestamp (" + testClassFileLastModified + "). Running test class tests.").await();
                            runTestClass = true;
                        }
                        else if (testJSONClassFile.getFailedTestCount() > 0)
                        {
                            verbose.writeLine("Previous run of " + testClassName + " contained errors. Running test class tests...").await();
                            runTestClass = true;
                        }
                        else
                        {
                            verbose.writeLine("Previous run of " + testClassName + " didn't contain errors and the test class hasn't changed since then. Skipping test class tests.").await();
                            runner.addUnmodifiedPassedTests(testJSONClassFile.getPassedTestCount());
                            runner.addUnmodifiedSkippedTests(testJSONClassFile.getSkippedTestCount());
                            testJSONClassFiles.addAll(testJSONClassFile);
                            runTestClass = false;
                        }
                    }
                }

                if (runTestClass)
                {
                    runner.testClass(testClassName)
                        .catchError((Throwable e) -> verbose.writeLine(e.getMessage()).await())
                        .await();
                }
            }

            if (useTestJson && pattern == null)
            {
                final File testJsonFile = outputFolder.getFile("test.json").await();
                final TestJSON testJson = TestJSON.create()
                    .setJavaVersion(currentJavaVersion)
                    .setClassFiles(testJSONClassFiles);
                testJsonFile.setContentsAsString(testJson.toString(JSONFormat.pretty)).await();
            }

            runner.writeLine().await();
            runner.writeSummary(stopwatch);

            result = runner.getFailedTestCount();
        }
        finally
        {
            if (logStreams != null)
            {
                logStreams.getLogStream().dispose().await();
            }
        }

        return result;
    }

    private final BasicTestRunner testRunner;
    private final IndentedCharacterToByteWriteStream writeStream;
    private int unmodifiedPassedTests;
    private int unmodifiedSkippedTests;

    public ConsoleTestRunner(DesktopProcess process, CharacterToByteWriteStream output, PathPattern pattern)
    {
        PreCondition.assertNotNull(process, "process");
        PreCondition.assertNotNull(output, "output");

        this.testRunner = BasicTestRunner.create(process, pattern);

        this.writeStream = IndentedCharacterToByteWriteStream.create(output);

        final List<TestParent> testParentsWrittenToConsole = List.create();
        this.testRunner.afterTestClass((TestClass testClass) ->
        {
            if (testParentsWrittenToConsole.remove(testClass))
            {
                this.decreaseIndent();
            }
        });
        this.testRunner.afterTestGroup((TestGroup testGroup) ->
        {
            if (testParentsWrittenToConsole.remove(testGroup))
            {
                this.decreaseIndent();
            }
        });
        this.testRunner.beforeTest((Test test) ->
        {
            final Stack<TestParent> testParentsToWrite = Stack.create();
            TestParent currentTestParent = test.getParent();
            while (currentTestParent != null && !testParentsWrittenToConsole.contains(currentTestParent))
            {
                testParentsToWrite.push(currentTestParent);
                currentTestParent = currentTestParent.getParent();
            }

            while (testParentsToWrite.any())
            {
                final TestParent testParentToWrite = testParentsToWrite.pop().await();

                final String skipMessage = testParentToWrite.getSkipMessage();
                final String testGroupMessage = testParentToWrite.getName() + (!testParentToWrite.shouldSkip() ? "" : " - Skipped" + (Strings.isNullOrEmpty(skipMessage) ? "" : ": " + skipMessage));
                this.writeStream.writeLine(testGroupMessage).await();
                testParentsWrittenToConsole.addAll(testParentToWrite);
                this.increaseIndent();
            }

            this.writeStream.write(test.getName()).await();
            this.increaseIndent();
        });
        this.testRunner.afterTestSuccess((Test test) ->
        {
            this.writeStream.writeLine(" - Passed").await();
        });
        this.testRunner.afterTestFailure((Test test, TestError failure) ->
        {
            this.writeStream.writeLine(" - Failed").await();
            this.writeFailure(failure);
        });
        this.testRunner.afterTestSkipped((Test test) ->
        {
            final String skipMessage = test.getSkipMessage();
            this.writeStream.writeLine(" - Skipped" + (Strings.isNullOrEmpty(skipMessage) ? "" : ": " + skipMessage)).await();
        });
        this.testRunner.afterTest((Test test) ->
        {
            this.decreaseIndent();
        });
    }

    private void addUnmodifiedPassedTests(int unmodifiedPassedTests)
    {
        PreCondition.assertGreaterThanOrEqualTo(unmodifiedPassedTests, 0, "unmodifiedPassedTests");

        this.unmodifiedPassedTests += unmodifiedPassedTests;
    }

    private void addUnmodifiedSkippedTests(int unmodifiedSkippedTests)
    {
        PreCondition.assertGreaterThanOrEqualTo(unmodifiedSkippedTests, 0, "unmodifiedSkippedTests");

        this.unmodifiedSkippedTests += unmodifiedSkippedTests;
    }

    /**
     * Increase the indent of the Console output.
     */
    private void increaseIndent()
    {
        this.writeStream.increaseIndent();
    }

    /**
     * Decrease the indent of the Console output.
     */
    private void decreaseIndent()
    {
        this.writeStream.decreaseIndent();
    }

    public int getFailedTestCount()
    {
        return this.testRunner.getFailedTestCount();
    }

    public void writeFailure(TestError failure)
    {
        PreCondition.assertNotNull(failure, "failure");

        this.increaseIndent();
        this.writeMessageLines(failure);
        this.writeStackTrace(failure);
        this.decreaseIndent();

        final Throwable cause = failure.getCause();
        if (cause != null)
        {
            this.writeFailureCause(cause);
        }
    }

    public void writeMessageLines(TestError failure)
    {
        PreCondition.assertNotNull(failure, "failure");

        for (final String messageLine : failure.getMessageLines())
        {
            if (messageLine != null)
            {
                this.writeStream.writeLine(messageLine).await();
            }
        }
    }

    private void writeMessage(Throwable throwable)
    {
        if (throwable instanceof TestError)
        {
            this.writeMessageLines((TestError)throwable);
        }
        else if (!Strings.isNullOrEmpty(throwable.getMessage()))
        {
            this.writeStream.writeLine("Message: " + throwable.getMessage()).await();
        }
    }

    private void writeFailureCause(Throwable cause)
    {
        if (cause instanceof ErrorIterable)
        {
            final ErrorIterable errors = (ErrorIterable)cause;

            this.writeStream.writeLine("Caused by:").await();
            int causeNumber = 0;
            for (final Throwable innerCause : errors)
            {
                ++causeNumber;
                this.writeStream.write(causeNumber + ") " + innerCause.getClass().getName()).await();

                this.increaseIndent();
                this.writeMessage(innerCause);
                this.writeStackTrace(innerCause);
                this.decreaseIndent();

                final Throwable nextCause = innerCause.getCause();
                if (nextCause != null && nextCause != innerCause)
                {
                    this.increaseIndent();
                    this.writeFailureCause(nextCause);
                    this.decreaseIndent();
                }
            }
        }
        else
        {
            writeStream.writeLine("Caused by: " + cause.getClass().getName()).await();

            this.increaseIndent();
            this.writeMessage(cause);
            this.writeStackTrace(cause);
            this.decreaseIndent();

            final Throwable nextCause = cause.getCause();
            if (nextCause != null && nextCause != cause)
            {
                this.increaseIndent();
                this.writeFailureCause(nextCause);
                this.decreaseIndent();
            }
        }
    }

    @Override
    public Skip skip()
    {
        return this.testRunner.skip();
    }

    @Override
    public Skip skip(boolean toSkip)
    {
        return this.testRunner.skip(toSkip);
    }

    @Override
    public Skip skip(boolean toSkip, String message)
    {
        return this.testRunner.skip(toSkip, message);
    }

    @Override
    public Skip skip(String message)
    {
        return this.testRunner.skip(message);
    }

    @Override
    public Result<Void> testClass(String fullClassName)
    {
        return this.testRunner.testClass(fullClassName);
    }

    @Override
    public Result<Void> testClass(Class<?> testClass)
    {
        return this.testRunner.testClass(testClass);
    }

    @Override
    public void testGroup(String testGroupName, Action0 testGroupAction)
    {
        this.testRunner.testGroup(testGroupName, testGroupAction);
    }

    @Override
    public void testGroup(Class<?> testClass, Action0 testGroupAction)
    {
        this.testRunner.testGroup(testClass, testGroupAction);
    }

    @Override
    public void testGroup(String testGroupName, Skip skip, Action0 testGroupAction)
    {
        this.testRunner.testGroup(testGroupName, skip, testGroupAction);
    }

    @Override
    public <T1> void testGroup(String testGroupName, Skip skip, Function1<TestResources, Tuple1<T1>> resourcesFunction, Action1<T1> testGroupAction)
    {
        this.testRunner.testGroup(testGroupName, skip, resourcesFunction, testGroupAction);
    }

    @Override
    public <T1, T2> void testGroup(String testGroupName, Skip skip, Function1<TestResources, Tuple2<T1, T2>> resourcesFunction, Action2<T1, T2> testGroupAction)
    {
        this.testRunner.testGroup(testGroupName, skip, resourcesFunction, testGroupAction);
    }

    @Override
    public <T1, T2, T3> void testGroup(String testGroupName, Skip skip, Function1<TestResources, Tuple3<T1, T2, T3>> resourcesFunction, Action3<T1, T2, T3> testGroupAction)
    {
        this.testRunner.testGroup(testGroupName, skip, resourcesFunction, testGroupAction);
    }

    @Override
    public void testGroup(Class<?> testClass, Skip skip, Action0 testGroupAction)
    {
        this.testRunner.testGroup(testClass, skip, testGroupAction);
    }

    @Override
    public void test(String testName, Action1<Test> testAction)
    {
        this.testRunner.test(testName, testAction);
    }

    @Override
    public void test(String testName, Skip skip, Action1<Test> testAction)
    {
        this.testRunner.test(testName, skip, testAction);
    }

    @Override
    public <T1> void test(String testName, Skip skip, Function1<TestResources, Tuple1<T1>> resourcesFunction, Action2<Test, T1> testAction)
    {
        this.testRunner.test(testName, skip, resourcesFunction, testAction);
    }

    @Override
    public <T1, T2> void test(String testName, Skip skip, Function1<TestResources, Tuple2<T1, T2>> resourcesFunction, Action3<Test, T1, T2> testAction)
    {
        this.testRunner.test(testName, skip, resourcesFunction, testAction);
    }

    @Override
    public <T1, T2, T3> void test(String testName, Skip skip, Function1<TestResources, Tuple3<T1, T2, T3>> resourcesFunction, Action4<Test, T1, T2, T3> testAction)
    {
        this.testRunner.test(testName, skip, resourcesFunction, testAction);
    }

    @Override
    public void speedTest(String testName, Duration maximumDuration, Action1<Test> testAction)
    {
        this.testRunner.speedTest(testName, maximumDuration, testAction);
    }

    @Override
    public void beforeTestClass(Action1<TestClass> beforeTestClassAction)
    {
        this.testRunner.beforeTestClass(beforeTestClassAction);
    }

    @Override
    public void afterTestClass(Action1<TestClass> afterTestClassAction)
    {
        this.testRunner.afterTestClass(afterTestClassAction);
    }

    @Override
    public void beforeTestGroup(Action1<TestGroup> beforeTestGroupAction)
    {
        this.testRunner.beforeTestGroup(beforeTestGroupAction);
    }

    @Override
    public void afterTestGroupFailure(Action2<TestGroup,TestError> afterTestGroupFailureAction)
    {
        this.testRunner.afterTestGroupFailure(afterTestGroupFailureAction);
    }

    @Override
    public void afterTestGroupSkipped(Action1<TestGroup> afterTestGroupSkipped)
    {
        this.testRunner.afterTestGroupSkipped(afterTestGroupSkipped);
    }

    @Override
    public void afterTestGroup(Action1<TestGroup> afterTestGroupAction)
    {
        this.testRunner.afterTestGroup(afterTestGroupAction);
    }

    @Override
    public void beforeTest(Action1<Test> beforeTestAction)
    {
        this.testRunner.beforeTest(beforeTestAction);
    }

    @Override
    public void afterTestFailure(Action2<Test,TestError> afterTestFailureAction)
    {
        this.testRunner.afterTestFailure(afterTestFailureAction);
    }

    @Override
    public void afterTestSuccess(Action1<Test> afterTestSuccessAction)
    {
        this.testRunner.afterTestSuccess(afterTestSuccessAction);
    }

    @Override
    public void afterTestSkipped(Action1<Test> afterTestSkippedAction)
    {
        this.testRunner.afterTestSkipped(afterTestSkippedAction);
    }

    @Override
    public void afterTest(Action1<Test> afterTestAction)
    {
        this.testRunner.afterTest(afterTestAction);
    }

    /**
     * Write the stack trace of the provided Throwable to the output stream.
     * @param t The Throwable to writeByte the stack trace of.
     */
    private void writeStackTrace(Throwable t)
    {
        final StackTraceElement[] stackTraceElements = t.getStackTrace();
        if (stackTraceElements != null && stackTraceElements.length > 0)
        {
            this.writeStream.writeLine("Stack Trace:");
            this.increaseIndent();
            for (StackTraceElement stackTraceElement : stackTraceElements)
            {
                this.writeStream.writeLine("at " + stackTraceElement.toString());
            }
            this.decreaseIndent();
        }
    }

    public Result<Integer> writeLine()
    {
        return this.writeStream.writeLine();
    }

    /**
     * Write the current statistics of this ConsoleTestRunner.
     */
    public void writeSummary(Stopwatch stopwatch)
    {
        PreCondition.assertNotNull(stopwatch, "stopwatch");

        final Iterable<Test> skippedTests = this.testRunner.getSkippedTests();
        if (skippedTests.any())
        {
            this.writeStream.writeLine("Skipped Tests:").await();
            this.increaseIndent();
            int testSkippedNumber = 1;
            for (final Test skippedTest : skippedTests)
            {
                final String skipMessage = skippedTest.getSkipMessage();
                this.writeStream.writeLine(testSkippedNumber + ") " + skippedTest.getFullName() + (Strings.isNullOrEmpty(skipMessage) ? "" : ": " + skipMessage)).await();
                ++testSkippedNumber;
            }
            this.decreaseIndent();

            this.writeStream.writeLine().await();
        }

        final Iterable<TestError> testFailures = this.testRunner.getTestFailures();
        if (testFailures.any())
        {
            this.writeStream.writeLine("Test failures:").await();
            increaseIndent();

            int testFailureNumber = 1;
            for (final TestError failure : testFailures)
            {
                this.writeStream.writeLine(testFailureNumber + ") " + failure.getTestScope()).await();
                ++testFailureNumber;
                this.increaseIndent();
                this.writeFailure(failure);
                this.decreaseIndent();

                this.writeStream.writeLine().await();
            }

            this.decreaseIndent();
        }

        final CharacterTable table = CharacterTable.create();
        if (this.unmodifiedPassedTests > 0 || this.unmodifiedSkippedTests > 0)
        {
            table.addRow("Unmodified Tests:", Integers.toString(this.unmodifiedPassedTests + this.unmodifiedSkippedTests));
            if (this.unmodifiedPassedTests > 0)
            {
                table.addRow("Unmodified Passed Tests:", Integers.toString(this.unmodifiedPassedTests));
            }
            if (this.unmodifiedSkippedTests > 0)
            {
                table.addRow("Unmodified Skipped Tests:", Integers.toString(this.unmodifiedSkippedTests));
            }
        }

        if (this.testRunner.getFinishedTestCount() > 0)
        {
            table.addRow("Tests Run:", Integers.toString(this.testRunner.getFinishedTestCount()));
            if (this.testRunner.getPassedTestCount() > 0)
            {
                table.addRow("Tests Passed:", Integers.toString(this.testRunner.getPassedTestCount()));
            }
            if (this.testRunner.getFailedTestCount() > 0)
            {
                table.addRow("Tests Failed:", Integers.toString(this.testRunner.getFailedTestCount()));
            }
            if (this.testRunner.getSkippedTestCount() > 0)
            {
                table.addRow("Tests Skipped:", Integers.toString(this.testRunner.getSkippedTestCount()));
            }
        }

        final Duration totalTestsDuration = stopwatch.stop();
        table.addRow("Tests Duration:", totalTestsDuration.toSeconds().toString("0.0"));

        table.toString(this.writeStream, CharacterTableFormat.consise).await();
        this.writeStream.writeLine().await();
    }
}

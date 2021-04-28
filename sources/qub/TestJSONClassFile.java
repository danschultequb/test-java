package qub;

/**
 * The saved result of running the tests for a Java class file.
 */
public class TestJSONClassFile
{
    private static final String lastModifiedPropertyName = "lastModified";
    private static final String passedTestCountPropertyName = "passedTestCount";
    private static final String skippedTestCountPropertyName = "skippedTestCount";
    private static final String failedTestCountPropertyName = "failedTestCount";

    private final JSONProperty jsonProperty;

    private TestJSONClassFile(JSONProperty jsonProperty)
    {
        PreCondition.assertNotNull(jsonProperty, "jsonProperty");

        this.jsonProperty = jsonProperty;
    }

    public static TestJSONClassFile create(String classFileRelativePath)
    {
        PreCondition.assertNotNullAndNotEmpty(classFileRelativePath, "classFileRelativePath");

        return TestJSONClassFile.create(Path.parse(classFileRelativePath));
    }

    public static TestJSONClassFile create(Path classFileRelativePath)
    {
        PreCondition.assertNotNull(classFileRelativePath, "classFileRelativePath");
        PreCondition.assertFalse(classFileRelativePath.isRooted(), "classFileRelativePath.isRooted()");

        return new TestJSONClassFile(JSONProperty.create(classFileRelativePath.toString(), JSONObject.create()));
    }

    public static Result<TestJSONClassFile> parse(JSONProperty property)
    {
        PreCondition.assertNotNull(property, "property");

        return Result.create(() ->
        {
            return new TestJSONClassFile(property);
        });
    }

    private JSONObject getPropertyValue()
    {
        return this.jsonProperty.getObjectValue().await();
    }

    /**
     * Get the path to the class file relative to the test.json file.
     * @return The path to the class file relative to the test.json file.
     */
    public Path getRelativePath()
    {
        return Path.parse(this.jsonProperty.getName());
    }

    /**
     * Get the full class name of the class file.
     * @return The full class name of the class file.
     */
    public String getFullClassName()
    {
        final String result = QubTestRun.getFullClassName(this.getRelativePath());

        PostCondition.assertNotNullAndNotEmpty(result, "result");

        return result;
    }

    /**
     * Set the last time that the class file was modified.
     * @param lastModified The last time that the class file was modified.
     * @return This object for method chaining.
     */
    public TestJSONClassFile setLastModified(DateTime lastModified)
    {
        PreCondition.assertNotNull(lastModified, "lastModified");

        this.getPropertyValue().setString(TestJSONClassFile.lastModifiedPropertyName, lastModified.toString());

        return this;
    }

    /**
     * Get the last time that the class file was modified.
     * @return The last time that the class file was modified.
     */
    public DateTime getLastModified()
    {
        return this.getPropertyValue()
            .getString(lastModifiedPropertyName)
            .then((String lastModified) -> DateTime.parse(lastModified).await())
            .catchError()
            .await();
    }

    public TestJSONClassFile setPassedTestCount(int passedTestCount)
    {
        PreCondition.assertGreaterThanOrEqualTo(passedTestCount, 0, "passedTestCount");

        this.getPropertyValue().setNumber(TestJSONClassFile.passedTestCountPropertyName, passedTestCount);

        return this;
    }

    public int getPassedTestCount()
    {
        return this.getPropertyValue().getInteger(TestJSONClassFile.passedTestCountPropertyName)
            .catchError(() -> 0)
            .await();
    }

    public TestJSONClassFile setSkippedTestCount(int skippedTestCount)
    {
        PreCondition.assertGreaterThanOrEqualTo(skippedTestCount, 0, "skippedTestCount");

        this.getPropertyValue().setNumber(TestJSONClassFile.skippedTestCountPropertyName, skippedTestCount);

        return this;
    }

    public int getSkippedTestCount()
    {
        return this.getPropertyValue().getInteger(TestJSONClassFile.skippedTestCountPropertyName)
            .catchError(() -> 0)
            .await();
    }

    public TestJSONClassFile setFailedTestCount(int failedTestCount)
    {
        PreCondition.assertGreaterThanOrEqualTo(failedTestCount, 0, "failedTestCount");

        this.getPropertyValue().setNumber(TestJSONClassFile.failedTestCountPropertyName, failedTestCount);

        return this;
    }

    public int getFailedTestCount()
    {
        return this.getPropertyValue().getInteger(TestJSONClassFile.failedTestCountPropertyName)
            .catchError(() -> 0)
            .await();
    }

    @Override
    public String toString()
    {
        return this.toJsonProperty().toString();
    }

    public JSONProperty toJsonProperty()
    {
        return this.jsonProperty;
    }
}

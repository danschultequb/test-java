package qub;

/**
 * The data of a test.json file.
 */
public class TestJSON extends JSONObjectWrapperBase
{
    private static final String javaVersionPropertyName = "javaVersion";
    private static final String classFilesPropertyName = "classFiles";

    private TestJSON(JSONObject json)
    {
        super(json);
    }

    public static TestJSON create()
    {
        return TestJSON.create(JSONObject.create());
    }

    public static TestJSON create(JSONObject rootObject)
    {
        PreCondition.assertNotNull(rootObject, "rootObject");

        return new TestJSON(rootObject);
    }

    /**
     * Parse a TestJSON object from the provided test.json file.
     * @param testJsonFile The test.json file to parse.
     * @return The parsed TestJSON object.
     */
    public static Result<TestJSON> parse(File testJsonFile)
    {
        PreCondition.assertNotNull(testJsonFile, "testJsonFile");

        return JSON.parseObject(testJsonFile)
            .then((JSONObject json) -> TestJSON.create(json));
    }

    public static Result<TestJSON> parse(ByteReadStream readStream)
    {
        PreCondition.assertNotNull(readStream, "readStream");
        PreCondition.assertNotDisposed(readStream, "readStream.isDisposed()");

        return JSON.parseObject(readStream)
            .then((JSONObject json) -> TestJSON.create(json));
    }

    public static Result<TestJSON> parse(CharacterReadStream readStream)
    {
        PreCondition.assertNotNull(readStream, "readStream");
        PreCondition.assertNotDisposed(readStream, "readStream.isDisposed()");

        return JSON.parseObject(readStream)
            .then((JSONObject json) -> TestJSON.create(json));
    }

    /**
     * Set the version of java that was used to run the tests.
     * @param javaVersion The version of java that was used to run the tests.
     * @return This object for method chaining.
     */
    public TestJSON setJavaVersion(String javaVersion)
    {
        PreCondition.assertNotNullAndNotEmpty(javaVersion, "javaVersion");

        return this.setJavaVersion(VersionNumber.parse(javaVersion).await());
    }

    /**
     * Set the version of java that was used to run the tests.
     * @param javaVersion The version of java that was used to run the tests.
     * @return This object for method chaining.
     */
    public TestJSON setJavaVersion(VersionNumber javaVersion)
    {
        PreCondition.assertNotNullAndNotEmpty(javaVersion, "javaVersion");

        this.json.setString(TestJSON.javaVersionPropertyName, javaVersion.toString());
        return this;
    }

    /**
     * Get the version of java that was used to run the tests.
     * @return The version of java that was used to run the tests.
     */
    public Result<VersionNumber> getJavaVersion()
    {
        return Result.create(() ->
        {
            final String javaVersionString = this.json.getString(TestJSON.javaVersionPropertyName).await();
            return VersionNumber.parse(javaVersionString).await();
        });
    }

    /**
     * Set the TestJSONClassFile objects for a test.json file.
     * @param classFiles The TestJSONClassFile objects for a test.json file.
     * @return This object for method chaining.
     */
    public TestJSON setClassFiles(Iterable<TestJSONClassFile> classFiles)
    {
        PreCondition.assertNotNull(classFiles, "classFiles");

        this.json.set(TestJSON.classFilesPropertyName, JSONObject.create()
            .setAll(classFiles.map(TestJSONClassFile::toJsonProperty)));

        return this;
    }

    /**
     * Get the TestJSONClassFile objects for a test.json file.
     * @return The TestJSONClassFile objects for a test.json file.
     */
    public Iterable<TestJSONClassFile> getClassFiles()
    {
        return this.json.getObject(TestJSON.classFilesPropertyName)
            .then((JSONObject classFilesJsonObject) ->
            {
                return classFilesJsonObject.getProperties()
                    .map((JSONProperty classFileJsonProperty) -> TestJSONClassFile.parse(classFileJsonProperty).await());
            })
            .catchError(() -> Iterable.create())
            .await();
    }
}

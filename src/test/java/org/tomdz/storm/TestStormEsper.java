package org.tomdz.storm;

import static backtype.storm.utils.Utils.tuple;
import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.generated.StormTopology;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;

@Test
public class TestStormEsper
{
    private LocalCluster cluster;

    @BeforeMethod(alwaysRun = true)
    public void setUp()
    {
        cluster = new LocalCluster();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        cluster.shutdown();
    }

    private void runTest(final List<TestSpout> spouts,
                         final EsperBolt bolt,
                         final Event... expectedData) throws Exception
    {
        final GatheringBolt gatheringBolt = new GatheringBolt();
        final StormTopology topology = new TestTopologyBuilder().addSpouts(spouts)
                                                                .setBolts(bolt, gatheringBolt)
                                                                .build();

        Config conf = new Config();
        conf.setDebug(true);

        cluster.submitTopology("test", conf, topology);

        await().atMost(10, SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return gatheringBolt.getGatheredData().size() == expectedData.length;
            }
        });

        Map<String, List<List<?>>> expected = new HashMap<String, List<List<?>>>();
        Map<String, List<List<?>>> actual = new HashMap<String, List<List<?>>>();

        for (Event event : expectedData) {
            List<List<?>> expectedForType = expected.get(event.type);
            if (expectedForType == null) {
                expectedForType = new ArrayList<List<?>>();
                expected.put(event.type, expectedForType);
            }
            expectedForType.add(event.data);
        }
        for (Tuple tuple : gatheringBolt.getGatheredData()) {
            int streamId = tuple.getSourceStreamId();
            String eventType = bolt.getEventTypeForStreamId(streamId);
            Fields fields = bolt.getFieldsForEventType(eventType);

            assertEquals(tuple.getFields().toList(), fields.toList());

            List<List<?>> actualForType = actual.get(eventType);
            if (actualForType == null) {
                actualForType = new ArrayList<List<?>>();
                actual.put(eventType, actualForType);
            }
            actualForType.add(tuple.getValues());
        }
        assertEquals(actual, expected);
    }

    @SuppressWarnings("unchecked")
    public void testSimple() throws Exception
    {
        TestSpout spout = new TestSpout(new Fields("a", "b"), tuple(4, 1), tuple(2, 3), tuple(1, 2), tuple(3, 4));
        EsperBolt esperBolt = new EsperBolt.Builder()
                                           .addInputAlias(1, 1, "Test")
                                           .setAnonymousOutput(1, "min", "max")
                                           .addStatement("select max(a) as max, min(b) as min from Test.win:length_batch(4)")
                                           .build();

        runTest(Arrays.asList(spout),
                esperBolt,
                new Event(esperBolt.getEventTypeForStreamId(1), 1, 4));
    }

    @SuppressWarnings("unchecked")
    public void testMultipleStatements() throws Exception
    {
        TestSpout spout = new TestSpout(new Fields("a", "b"), tuple(4, 1), tuple(2, 3), tuple(1, 2), tuple(3, 4));
        EsperBolt esperBolt = new EsperBolt.Builder()
                                           .addInputAlias(1, 1, "Test")
                                           .addNamedOutput(1, "MaxValue", "max")
                                           .addNamedOutput(2, "MinValue", "min")
                                           .addStatement("insert into MaxValue select max(a) as max from Test.win:length_batch(4)")
                                           .addStatement("insert into MinValue select min(b) as min from Test.win:length_batch(4)")
                                           .build();

        runTest(Arrays.asList(spout),
                esperBolt,
                new Event("MaxValue", 4), new Event("MinValue", 1));
    }

    @SuppressWarnings("unchecked")
    public void testMultipleSpouts() throws Exception
    {
        TestSpout spout1 = new TestSpout(new Fields("a"), tuple(4), tuple(2), tuple(1), tuple(3));
        TestSpout spout2 = new TestSpout(new Fields("b"), tuple(1), tuple(3), tuple(2), tuple(4));
        EsperBolt esperBolt = new EsperBolt.Builder()
                                           .addInputAlias(1, 1, "TestA")
                                           .addInputAlias(2, 1, "TestB")
                                           .setAnonymousOutput(1, "min", "max")
                                           .addStatement("select max(a) as max, min(b) as min from TestA.win:length_batch(4), TestB.win:length_batch(4)")
                                           .build();

        runTest(Arrays.asList(spout1, spout2),
                esperBolt,
                new Event(esperBolt.getEventTypeForStreamId(1), 1, 4));
    }

    @SuppressWarnings("unchecked")
    public void testNoInputAlias() throws Exception
    {
        TestSpout spout = new TestSpout(new Fields("a", "b"), tuple(4, 1), tuple(2, 3), tuple(1, 2), tuple(3, 4));
        EsperBolt esperBolt = new EsperBolt.Builder()
                                           .setAnonymousOutput(1, "min", "max")
                                           .addStatement("select max(a) as max, min(b) as min from Storm.win:length_batch(4)")
                                           .build();

        runTest(Arrays.asList(spout),
                esperBolt,
                new Event(esperBolt.getEventTypeForStreamId(1), 1, 4));
    }

    @SuppressWarnings("unchecked")
    public void testMultipleSpoutsWithoutInputAlias() throws Exception
    {
        TestSpout spout1 = new TestSpout(new Fields("a"), tuple(4), tuple(2), tuple(1), tuple(3));
        TestSpout spout2 = new TestSpout(new Fields("b"), tuple(1), tuple(3), tuple(2), tuple(4));
        EsperBolt esperBolt = new EsperBolt.Builder()
                                           .addInputAlias(1, 1, "TestA")
                                           .setAnonymousOutput(1, "min", "max")
                                           .addStatement("select max(a) as max, min(b) as min from TestA.win:length_batch(4), Storm_2_1.win:length_batch(4)")
                                           .build();

        runTest(Arrays.asList(spout1, spout2),
                esperBolt,
                new Event(esperBolt.getEventTypeForStreamId(1), 1, 4));
    }

    public void testNoSuchSpout() throws Exception
    {
        EsperBolt esperBolt = new EsperBolt.Builder()
                                           .setAnonymousOutput(1, "min", "max")
                                           .addStatement("select max(a) as max, min(b) as min from Storm.win:length_batch(4)")
                                           .build();

        runTest(new ArrayList<TestSpout>(),
                esperBolt,
                new Event(esperBolt.getEventTypeForStreamId(1), 1, 4));
    }
    // no such event stream
    // adding alias for undefined spout
    // using the same alias twice
    // using same stream id twice for named output
}

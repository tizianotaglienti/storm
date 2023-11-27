package org.apache.storm.coordination;

import org.apache.storm.Config;
import org.apache.storm.Constants;
import org.apache.storm.generated.GlobalStreamId;
import org.apache.storm.generated.Grouping;
import org.apache.storm.task.GeneralTopologyContext;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;


@RunWith(Parameterized.class)
public class TestCoordinatedBolt {

    private String srcComponent;
    private String streamId;
    private Integer topologyTimeout;
    private List<Object> data;
    private CoordinatedBolt.SourceArgs sourceArgs;
    private CoordinatedBolt.IdStreamSpec idStreamSpec;

    public TestCoordinatedBolt(TestInput ti) {

        this.srcComponent = ti.getSrcComponent();
        this.streamId = ti.getStreamId();
        this.topologyTimeout = ti.getTopologyTimeout();
        this.data = ti.getData();
        this.sourceArgs = ti.getSourceArgs();
        this.idStreamSpec = ti.getIdStreamSpec();
    }

    @Parameterized.Parameters
    public static Collection<TestInput> getTestParameters() {
        Collection<TestInput> inputs = new ArrayList<>();

        List<Object> objectList1 = new ArrayList<>();
        objectList1.add("");
        objectList1.add(30);

        List<Object> objectList2 = new ArrayList<>();
        objectList2.add("test");
        objectList2.add(20);
        
        inputs.add(new TestInput("", "streamTest", -1, objectList1, CoordinatedBolt.SourceArgs.all(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("", "streamTest")));
        inputs.add(new TestInput("srcTest", "streamTest", 10, objectList1, CoordinatedBolt.SourceArgs.all(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", "streamTest")));
        inputs.add(new TestInput("srcTest", "", -1, objectList1, CoordinatedBolt.SourceArgs.all(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", "")));
        inputs.add(new TestInput("srcTest", Constants.COORDINATED_STREAM_ID, 0, objectList1, CoordinatedBolt.SourceArgs.all(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", Constants.COORDINATED_STREAM_ID)));
        inputs.add(new TestInput("", Constants.COORDINATED_STREAM_ID, 10, objectList1, CoordinatedBolt.SourceArgs.all(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("", Constants.COORDINATED_STREAM_ID)));

        inputs.add(new TestInput("", Constants.COORDINATED_STREAM_ID, 10, objectList2, CoordinatedBolt.SourceArgs.single(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("", Constants.COORDINATED_STREAM_ID)));
        inputs.add(new TestInput(null, "streamTest", 0, objectList2, CoordinatedBolt.SourceArgs.single(), null));
        inputs.add(new TestInput("srcTest", "", -1, objectList2, CoordinatedBolt.SourceArgs.single(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", "")));
        inputs.add(new TestInput(null, "", -1, objectList2, CoordinatedBolt.SourceArgs.all(), null));
        inputs.add(new TestInput("srcTest", "streamTest", null, objectList2, CoordinatedBolt.SourceArgs.single(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", "streamTest")));

        inputs.add(new TestInput("srcTest", "streamTest", 0, objectList1, CoordinatedBolt.SourceArgs.single(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", "streamTest")));
        inputs.add(new TestInput("srcTest", "streamTest", -1, objectList1, CoordinatedBolt.SourceArgs.single(), CoordinatedBolt.IdStreamSpec.makeDetectSpec("srcTest", "streamTest")));
        inputs.add(new TestInput("srcTest", "streamTest", 10, objectList2, CoordinatedBolt.SourceArgs.all(), null));
        inputs.add(new TestInput("srcTest", Constants.COORDINATED_STREAM_ID, 10, objectList2, CoordinatedBolt.SourceArgs.all(), null));
        inputs.add(new TestInput("srcTest", "", null, objectList2, CoordinatedBolt.SourceArgs.all(), null));

        return inputs;
    }


    @Test
    public void testPrepareAndExecute() {

        MockRichBoltTimeout mrb = new MockRichBoltTimeout();
        CoordinatedBolt cb = new CoordinatedBolt(mrb, this.srcComponent, this.sourceArgs, this.idStreamSpec);


        TopologyContext context = mock(TopologyContext.class);
        Map<String, Map<String, Grouping>> map = new HashMap<>();
        Map<String, Grouping> mapG = new HashMap<>();
        mapG.put("stringTest", Grouping.custom_serialized("stringTest".getBytes(StandardCharsets.UTF_8)));
        map.put("stringTest", mapG);
        when(context.maxTopologyMessageTimeout()).thenAnswer(invocation -> this.topologyTimeout);
        when(context.getThisTargets()).thenAnswer(invocation -> map);
        List<Integer> integerList = new ArrayList<>();
        integerList.add(12);
        integerList.add(30);
        when(context.getComponentTasks(anyString())).thenAnswer(invocation -> integerList);

        Map<GlobalStreamId, Grouping> sources = Collections.singletonMap(new GlobalStreamId(this.srcComponent, this.streamId), Grouping.custom_serialized("stringTest".getBytes(StandardCharsets.UTF_8)));
        when(context.getThisSources()).thenReturn(sources);

        Map<String, Object> conf = new HashMap<>();

        OutputCollector oc = mock(OutputCollector.class);
        cb.prepare(conf, context, oc);

        TopologyBuilder builder = new TopologyBuilder();
        GeneralTopologyContext gtc = new GeneralTopologyContext(builder.createTopology(),
                new Config(), new HashMap<>(), new HashMap<>(), new HashMap<>(), "") {
            @Override
            public Fields getComponentOutputFields(String componentId, String streamId) {
                return new Fields("key", "value");
            }

        };


        TupleImpl rec = new TupleImpl(gtc, this.data, this.srcComponent, 1, this.streamId);

        cb.execute(rec);

        if (idStreamSpec != null)
            Assert.assertTrue(this.idStreamSpec.getGlobalStreamId().get_componentId().equals(this.srcComponent) && this.idStreamSpec.getGlobalStreamId().get_streamId().equals(this.streamId));

        cb.cleanup();
    }


    @Test
    public void testPrepareAndExecuteDiffConstructor() {

        MockRichBolt mrb = new MockRichBolt();
        CoordinatedBolt cb = new CoordinatedBolt(mrb);


        TopologyContext context = mock(TopologyContext.class);
        Map<String, Map<String, Grouping>> map = new HashMap<>();
        Map<String, Grouping> mapG = new HashMap<>();
        mapG.put("stringTest", Grouping.custom_serialized("stringTest".getBytes(StandardCharsets.UTF_8)));
        map.put("stringTest", mapG);
        when(context.maxTopologyMessageTimeout()).thenAnswer(invocation -> null);
        when(context.getThisTargets()).thenAnswer(invocation -> map);
        List<Integer> integerList = new ArrayList<>();
        integerList.add(12);
        integerList.add(30);
        integerList.add(45);
        when(context.getComponentTasks(anyString())).thenAnswer(invocation -> integerList);

        Map<GlobalStreamId, Grouping> sources = Collections.singletonMap(new GlobalStreamId(this.srcComponent, this.streamId), Grouping.custom_serialized("stringTest".getBytes(StandardCharsets.UTF_8)));
        when(context.getThisSources()).thenReturn(sources);

        Map<String, Object> conf = new HashMap<>();

        OutputCollector oc = mock(OutputCollector.class);
        cb.prepare(conf, context, oc);

        TopologyBuilder builder = new TopologyBuilder();
        GeneralTopologyContext gtc = new GeneralTopologyContext(builder.createTopology(),
                new Config(), new HashMap<>(), new HashMap<>(), new HashMap<>(), "") {
            @Override
            public Fields getComponentOutputFields(String componentId, String streamId) {
                return new Fields("key", "value");
            }

        };


        TupleImpl rec = new TupleImpl(gtc, this.data, this.srcComponent, 1, this.streamId);

        cb.execute(rec);


        Assert.assertEquals((this.data.size()) / 2, mrb.actualResults.size());


        for (String tuple : mrb.actualResults) {
            Assert.assertNotNull(tuple);
        }

        cb.cleanup();

    }


    private static class TestInput {

        private String srcComponent;
        private String streamId;
        private Integer topologyTimeout;
        private List<Object> data;
        private CoordinatedBolt.SourceArgs sourceArgs;
        private CoordinatedBolt.IdStreamSpec idStreamSpec;

        public TestInput(String srcComponent, String streamId, Integer topologyTimeout, List<Object> data, CoordinatedBolt.SourceArgs sourceArgs, CoordinatedBolt.IdStreamSpec idStreamSpec) {
            this.srcComponent = srcComponent;
            this.streamId = streamId;
            this.topologyTimeout = topologyTimeout;
            this.data = data;
            this.sourceArgs = sourceArgs;
            this.idStreamSpec = idStreamSpec;
        }

        public String getSrcComponent() {
            return srcComponent;
        }

        public String getStreamId() {
            return streamId;
        }

        public Integer getTopologyTimeout() {
            return topologyTimeout;
        }

        public List<Object> getData() {
            return data;
        }

        public CoordinatedBolt.SourceArgs getSourceArgs() {
            return sourceArgs;
        }

        public CoordinatedBolt.IdStreamSpec getIdStreamSpec() {
            return idStreamSpec;
        }


    }

    private static class MockRichBoltTimeout extends MockRichBolt implements CoordinatedBolt.TimeoutCallback {

        @Override
        public void timeoutId(Object id) {

        }
    }

    private static class MockRichBolt implements IRichBolt {

        public List<String> actualResults = new ArrayList<>();

        @Override
        public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {

        }

        @Override
        public void execute(Tuple input) {

            for (int i = 0; i < 1; i++) {
                actualResults.add(input.getString(i));
            }


        }

        @Override
        public void cleanup() {

        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {

        }

        @Override
        public Map<String, Object> getComponentConfiguration() {
            return null;
        }
    }


}
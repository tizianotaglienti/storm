package org.apache.storm.topology;

import java.util.*;

import org.apache.storm.Config;
import org.apache.storm.generated.GlobalStreamId;
import org.apache.storm.generated.Grouping;
import org.apache.storm.task.GeneralTopologyContext;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.base.BaseWindowedBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.apache.storm.windowing.TimestampExtractor;
import org.apache.storm.windowing.TupleWindow;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class TestWindowedBoltExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TestWindowedBoltExecutor.class);
    private String fieldName;
    private long[] timestamps;
    private String componentId;
    private String srcComponent;
    private Integer taskId;
    private Map<String,Optional<?>> restoreState;
    private Map<String, Object> conf;
    private MockWindowedBolt boltMock;
    private Set<String> setString;

    public TestWindowedBoltExecutor(TestInput ti) {
        this.fieldName = ti.getFieldName();
        this.timestamps = ti.getTimestamps();
        this.componentId = ti.getComponentId();
        this.srcComponent = ti.getSrcComponent();
        this.taskId = ti.getTaskId();
        this.restoreState = ti.getRestoreState();
        this.conf = ti.getConf();
        this.boltMock = ti.getBoltMock();
        this.setString = ti.getSetString();

    }

    @Parameterized.Parameters
    public static Collection<TestInput> getTestParameters() {
        Collection<TestInput> inputs = new ArrayList<>();

        Map<String,Optional<?>> state = new HashMap<>();
        state.put("ts", Optional.of(123L));

        Map<String, Object> typeConf = new HashMap<>();
        typeConf.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 10000);
        typeConf.put(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS, 999);
        typeConf.put(Config.TOPOLOGY_BOLTS_SLIDING_INTERVAL_DURATION_MS, 30);
        typeConf.put(Config.TOPOLOGY_BOLTS_TUPLE_TIMESTAMP_MAX_LAG_MS, 50);
        // trigger manually to avoid timing issues
        typeConf.put(Config.TOPOLOGY_BOLTS_WATERMARK_EVENT_INTERVAL_MS, 30);


        Map<String, Object> typeConf2 = new HashMap<>();
        typeConf2.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 10000);
        typeConf2.put(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_DURATION_MS, 999);


        Map<String, Object> typeConf3 = new HashMap<>();
        typeConf3.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 10000);
        typeConf3.put(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_COUNT, 10);
        typeConf3.put(Config.TOPOLOGY_BOLTS_SLIDING_INTERVAL_COUNT, 5);

        MockWindowedBolt mwb = new MockWindowedBolt();
        mwb.withTimestampField("ts");
        mwb.setTimeStamp("test");

        MockWindowedBolt mwbNull = new MockWindowedBolt();
        mwbNull.withTimestampField("ts");
        mwbNull.setTimeStamp(null);

        Map<String, Object> typeConf4 = new HashMap<>();
        typeConf4.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 10000);
        typeConf4.put(Config.TOPOLOGY_BOLTS_WINDOW_LENGTH_COUNT, 10);
        typeConf4.put(Config.TOPOLOGY_BOLTS_SLIDING_INTERVAL_COUNT, 5);
        typeConf4.put(Config.TOPOLOGY_BOLTS_LATE_TUPLE_STREAM, "stream");

        Set<String> str = new HashSet<>();
        str.add("stream");


        Set<String> strMod = new HashSet<>();

        long[] timeStamps = new long[]{240,2049,2944,29484,2992};

        inputs.add(new TestInput(typeConf,"ts",timeStamps,"source","s1",1, state,mwb,str));
        inputs.add(new TestInput(typeConf,"ts",timeStamps,"source","s1",1, state,mwbNull,str));
        inputs.add(new TestInput(typeConf2,"ts",timeStamps,"source","s1",1, state,mwb,str));
        inputs.add(new TestInput(typeConf3,"ts",timeStamps,"source","s1",1, state,mwb,str));
        inputs.add(new TestInput(typeConf4,"ts",timeStamps,"source","s1",1, state,mwbNull,strMod));
        inputs.add(new TestInput(typeConf4,"ts",timeStamps,"source","s1",1, state,mwb,strMod));

        return inputs;

    }


    @Test
    public void testExecuteWithTs() {

        WindowedBoltExecutor executor = new WindowedBoltExecutor(this.boltMock);

        TopologyContext context = mock(TopologyContext.class);

        Map<GlobalStreamId, Grouping> sources = Collections.singletonMap(new GlobalStreamId(this.componentId, "default"), null);
        when(context.getThisSources()).thenReturn(sources);
        when(context.getThisStreams()).thenReturn(setString);

        if(conf.containsKey(Config.TOPOLOGY_BOLTS_LATE_TUPLE_STREAM) || !setString.contains("stream")){
            assertThrows(IllegalArgumentException.class, () -> executor.prepare(conf,context,mock(OutputCollector.class)));
        }else{
            executor.prepare(conf, context, mock(OutputCollector.class));
        }

        TopologyBuilder builder = new TopologyBuilder();
        GeneralTopologyContext gtc = new GeneralTopologyContext(builder.createTopology(),
                new Config(), new HashMap<>(), new HashMap<>(), new HashMap<>(), "") {
            @Override
            public Fields getComponentOutputFields(String componentId, String streamId) {
                return new Fields(fieldName);
            }

        };


        for (long ts : timestamps) {
            if(conf.containsKey(Config.TOPOLOGY_BOLTS_LATE_TUPLE_STREAM)){
                assertThrows(NullPointerException.class, () -> executor.execute(new TupleImpl(gtc,new Values(ts), this.srcComponent,this.taskId,this.componentId)));
            }else {
                executor.execute(new TupleImpl(gtc, new Values(ts), this.srcComponent, this.taskId, this.componentId));
            }
        }

        if(conf.containsKey(Config.TOPOLOGY_BOLTS_LATE_TUPLE_STREAM)){
            assertThrows(NullPointerException.class, () -> executor.waterMarkEventGenerator.run());
        }else if(!conf.containsKey(Config.TOPOLOGY_BOLTS_LATE_TUPLE_STREAM) && this.boltMock.getTimeStamp() == null) {
            assertNotNull(executor);
        }else {
            executor.waterMarkEventGenerator.run();
            assertTrue(this.boltMock.tupleWindows.size() > 0);

            TupleWindow first = this.boltMock.tupleWindows.get(0);

            assertArrayEquals(new long[]{this.timestamps[0]}, new long[]{(long) first.get().get(0).getValue(0)});

            executor.cleanup();
        }
    }

    private static class TestInput {

        private String fieldName;
        private long[] timestamps;
        private String componentId;
        private String srcComponent;
        private Integer taskId;
        private Map<String,Optional<?>> restoreState;
        private Map<String, Object> conf;
        private MockWindowedBolt boltMock;
        private Set<String> setString;


        public TestInput(Map<String, Object> conf,String fieldName, long[] timestamps, String componentId, String srcComponent, Integer taskId, Map<String, Optional<?>> restoreState, MockWindowedBolt mock, Set<String> str) {            this.fieldName = fieldName;
            this.timestamps = timestamps;
            this.componentId = componentId;
            this.srcComponent = srcComponent;
            this.taskId = taskId;
            this.restoreState = restoreState;
            this.conf = conf;
            this.boltMock = mock;
            this.setString = str;
        }


        public Set<String> getSetString() {
            return setString;
        }
        public MockWindowedBolt getBoltMock() {
            return boltMock;
        }

        public Map<String, Object> getConf() {
            return conf;
        }

        public String getFieldName() {
            return fieldName;
        }

        public long[] getTimestamps() {
            return timestamps;
        }

        public String getComponentId() {
            return componentId;
        }

        public String getSrcComponent() {
            return srcComponent;
        }

        public Integer getTaskId() {
            return taskId;
        }

        public Map<String, Optional<?>> getRestoreState() {
            return restoreState;
        }
    }


    private static class MockWindowedBolt extends BaseWindowedBolt {
        private List<TupleWindow> tupleWindows = new ArrayList<>();
        private String timeStamp;

        public String getTimeStamp() {
            return timeStamp;
        }

        public void setTimeStamp(String timeStamp) {
            this.timeStamp = timeStamp;
        }
        @Override
        public void execute(TupleWindow input) {

            tupleWindows.add(input);
        }

        @Override
        public TimestampExtractor getTimestampExtractor() {
            if(getTimeStamp() == null)
                return null;
            else
                return super.getTimestampExtractor();
        }

    }
}
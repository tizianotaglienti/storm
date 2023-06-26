package org.apache.storm.bolt;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.bolt.JoinBolt.Selector;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.GeneralTopologyContext;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.apache.storm.windowing.TupleWindow;
import org.apache.storm.windowing.TupleWindowImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class TestJoinBolt {

    private String[] userFields;
    private Object[][] users;
    private String[] orderFields;

    private Object[][] orders;

    private String[] storeFields;
    private Object[][] stores;

    private Selector type;
    private String srcOnStreamId;
    private String fieldName;

    private String stringId;
    private String fileName;
    private String spoutId;
    private String spoutSecondId;
    private String boltId;

    private String selectedColumn;
    private String stream2;
    private String streamJoin;
    private String priorStream;
    private String selectedColLeftJoin;
    private String selectedColJoin;
    private String priorStreamJoin;
    private static String componentId;

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }


    @SafeVarargs
    private static TupleWindow makeTupleWindow(ArrayList<Tuple>... streams) {
        ArrayList<Tuple> combined = null;
        for (int i = 0; i < streams.length; i++) {
            if (i == 0) {
                combined = new ArrayList<>(streams[0]);
            } else {
                combined.addAll(streams[i]);
            }
        }
        return new TupleWindowImpl(combined, null, null);
    }

    private static ArrayList<Tuple> makeStream(String streamName, String[] fieldNames, Object[][] data, String srcComponentName) {
        ArrayList<Tuple> result = new ArrayList<>();
        GeneralTopologyContext mockContext = Mockito.mock(GeneralTopologyContext.class);

        Mockito.when(mockContext.getComponentId(Mockito.any())).thenReturn(componentId);

        for (Object[] record : data) {
            TupleImpl rec = new TupleImpl(mockContext, Arrays.asList(record), srcComponentName, 0, streamName);
            result.add(rec);
        }

        return result;
    }


    @Test
    public void testBoltAndSpoutMock() throws Exception {
//        ArrayList<Tuple> orderStream = makeStream(this.srcOrStreamId, this.orderFields, this.orders, "ordersSpout");
//        TupleWindow window = makeTupleWindow(orderStream);
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout(this.spoutId, new MockBaseRichSpout(this.fileName),1);
        builder.setSpout(this.spoutSecondId, new MockBaseRichSpout(this.fileName),1);
        JoinBolt bolt = new JoinBolt(this.srcOnStreamId,"line")
                .select(this.selectedColumn);
        builder.setBolt(this.boltId, bolt,2).shuffleGrouping(this.spoutId).shuffleGrouping(this.spoutSecondId);
        Config config = new Config();
        StormSubmitter.submitTopology("line", config, builder.createTopology());

        MockCollector collector = new MockCollector();
        bolt.prepare(null, null, collector);

        File file = new File(this.fileName);
        try (Scanner scanner = new Scanner(file)){

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                Assert.assertTrue(collector.actualResults.contains(line)); //verificare
            }
        }


    }

    @Test
    public void testInnerJoin() throws Exception {
        ArrayList<Tuple> userStream = makeStream("users", userFields, users, "usersSpout");
        ArrayList<Tuple> orderStream = makeStream("orders", orderFields, orders, "ordersSpout");
        TupleWindow window = makeTupleWindow(orderStream, userStream);

        JoinBolt bolt = new JoinBolt(this.type, this.srcOnStreamId, this.fieldName)
                .join(this.streamJoin, this.fieldName, this.priorStreamJoin)
                .select(this.selectedColJoin);

        MockCollector collector = new MockCollector();
        bolt.prepare(null, null, collector);
        bolt.execute(window);

        for(int i = 0; i< users[0].length; i++) { //cambiare users[0]
            Assert.assertTrue(collector.actualResults.contains(users[0][i]));
        }
    }


    @Test
    public void testLeftJoin() throws Exception {
        ArrayList<Tuple> userStream = makeStream("users", this.userFields, this.users, "usersSpout");
        ArrayList<Tuple> orderStream = makeStream("orders", this.orderFields, this.orders, "ordersSpout");
        TupleWindow window = makeTupleWindow(orderStream, userStream);

        JoinBolt bolt = new JoinBolt(this.type, this.srcOnStreamId, this.fieldName) //userFields[0]
                .leftJoin(this.stream2,this.fieldName,this.priorStream) //"users"
                .select(this.selectedColLeftJoin);//"userId,name,price"

        MockCollector collector = new MockCollector();
        bolt.prepare(null, null, collector);
        bolt.execute(window);

        for(int i = 0; i< users[0].length; i++) { //cambiare users[0]
            Assert.assertTrue(collector.actualResults.contains(users[0][i]));
        }

    }


    static class MockCollector extends OutputCollector {
        private ArrayList<List<Object>> actualResults = new ArrayList<>();

        public MockCollector() {
            super(null);
        }

        @Override
        public List<Integer> emit(Collection<Tuple> anchors, List<Object> tuple) {
            actualResults.add(tuple);
            return null;
        }

    }


    static class MockBaseRichSpout extends BaseRichSpout{

        private SpoutOutputCollector collector;
        private String fileName;
        private BufferedReader in;

        public MockBaseRichSpout(String fileName) {
            this.fileName = fileName;
        }

        private static final long serialVersionUID = 1L;

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("line"));
        }

        @Override
        public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
            this.collector = collector;
            try {
                in = new BufferedReader(new FileReader(fileName));
            } catch (FileNotFoundException e){
                e.printStackTrace();
            }
        }

        @Override
        public void nextTuple() {

            if (in == null) return;
            String line = null;

            try {
                line = in.readLine();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (line != null) {
                collector.emit(new Values(line));
            }

        }



    }


}
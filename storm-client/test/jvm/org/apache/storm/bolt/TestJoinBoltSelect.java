package org.apache.storm.bolt;

import org.apache.storm.task.GeneralTopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.windowing.TupleWindow;
import org.apache.storm.windowing.TupleWindowImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public class TestJoinBoltSelect {

    static String[] streamFields = {"field1", "field2"};
    static Object[][] streamData = {
            {1, "data1"},
            {2, "data2"},
            {3, null},
            {null, "data4"}
    };

    private static CustomCollector mockedCollector;
    private static ArrayList<Tuple> inputStream;
    private static TupleWindow window;
    private static JoinBolt bolt;

    private String commaSeparatedValues;
    private Object expectedResult;

    public TestJoinBoltSelect(String commaSeparatedValues, Object expectedResult) {
        this.commaSeparatedValues = commaSeparatedValues;
        this.expectedResult = expectedResult;
    }

    @Parameters
    public static List<Object[]> testJoinBoltSelectData() {
        return Arrays.asList(new Object[][] {
                {"field1,field2", transformStreamData()},
                {"field1", transformStreamDataFirstOnly()},
                {"", nullStream()},
                {null, NullPointerException.class},
                {"notExistingField!", nullStream()},
                {"field1,field2,field3", transformStreamDataPlusNull()}
        });
    }

    private static ArrayList<List<Object>> transformStreamDataFirstOnly() {
        ArrayList<List<Object>> result = new ArrayList<>();
        for (int i = 0; i < streamData.length; i++) {
            Object[] newEntry = {streamData[i][0]};
            List<Object> entries = new ArrayList<>(Arrays.asList(newEntry));
            result.add(entries);
        }
        return result;
    }

    private static ArrayList<List<Object>> transformStreamData() {
        ArrayList<List<Object>> result = new ArrayList<>();
        for (int i = 0; i < streamData.length; i++) {
            List<Object> entries = new ArrayList<>(Arrays.asList(streamData[i]));
            result.add(entries);
        }
        return result;
    }

    private static ArrayList<List<Object>> nullStream() {
        ArrayList<List<Object>> result = new ArrayList<>();
        for (int i = 0; i < streamData.length; i++) {
            List<Object> entries = new ArrayList<>(Arrays.asList(new Object[]{null}));
            result.add(entries);
        }
        return result;
    }

    private static ArrayList<List<Object>> transformStreamDataPlusNull() {
        ArrayList<List<Object>> result = new ArrayList<>();
        for (int i = 0; i < streamData.length; i++) {
            Object[] newEntry = {streamData[i][0], streamData[i][1], null};
            List<Object> entries = new ArrayList<>(Arrays.asList(newEntry));
            result.add(entries);
        }
        return result;
    }

    private ArrayList<Tuple> createNewStream(String streamName, String[] fieldNames, Object[][] data, String srcComponentName) {
        GeneralTopologyContext mockContext = new CustomContext(fieldNames);
        ArrayList<Tuple> stream = new ArrayList<>();
        for (Object[] entry : data) {
            TupleImpl rec = new TupleImpl(mockContext, Arrays.asList(entry), srcComponentName, 0, streamName);
            stream.add(rec);
        }
        return stream;
    }

    private TupleWindow createNewWindowForSingleStream(ArrayList<Tuple> stream) {
        return new TupleWindowImpl(stream, null, null);
    }

    @Before
    public void configure() {
        mockedCollector = new CustomCollector();
        inputStream = createNewStream("streamName", streamFields, streamData, "streamSpout");
        window = createNewWindowForSingleStream(inputStream);
        bolt = new JoinBolt(JoinBolt.Selector.STREAM, "streamName", streamFields[0]);
    }

    @Test
    public void testJoinBoltSelect() {
        try {
            bolt.select(commaSeparatedValues);
            bolt.prepare(null, null, mockedCollector);
            bolt.execute(window);

            assertEquals((ArrayList<Object>) expectedResult, mockedCollector.outputs);
        } catch (Exception e) {
            assertEquals(expectedResult, e.getClass());
        }
    }
}

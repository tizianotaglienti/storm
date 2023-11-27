package org.apache.storm.bolt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.storm.bolt.TestJoinBoltJoin.StreamGenerator.TupleStream;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@RunWith(Parameterized.class)
public class TestJoinBoltJoin {

    static String[] reservationsFields = { "tableNumber", "name" };
    static Object[][] reservations = {
            { 1, "Smith" },
            { 2, "Johnson" },
            { 3, "Williams" },
            { 4, "Brown" },
            { 5, "Jones" },
            { 6, null }
    };

    static String[] menuFields = { "item", "price" };
    static Object[][] menu = {
            { "burger", 8.00 },
            { "soda", 1.50 },
            { "pizza", 12.00 },
            { "salad", 6.50 },
            { "water", 1.50 },
            { "fries", null }
    };

    static String[] ordersFields = { "reservation", "item" };
    static Object[][] orders = {
            { 1, "burger" },
            { 2, "soda" },
            { 1, "pizza" },
            { 3, "salad" },
            { 3, "fries" }
    };

    static String[] emptyFields = { "x", "y" };
    static Object[][] empty = {
            { null, null },
            { null, null },
    };

    private CustomCollector mockedCollector;

    private TupleStream stream1;
    private TupleStream stream2;
    private TupleStream stream3;
    private JoinBolt bolt;
    private JOINTYPE jointype;
    private Object expectedResult;

    public TestJoinBoltJoin(STREAM secondStream, int field2Index, STREAM thirdStream, int field3Index, JOINTYPE jointype,
                            Object expectedResult) {
        this.jointype = jointype;
        this.expectedResult = expectedResult;
        this.stream1 = StreamGenerator.createStream(STREAM.MENU, 0);
        this.stream2 = StreamGenerator.createStream(secondStream, field2Index);

        //this.stream3 = StreamGenerator.createStream(thirdStream, field3Index);

        mockedCollector = new CustomCollector();
        stream1 = StreamGenerator.createStream(STREAM.MENU, 0);
        bolt = new JoinBolt(JoinBolt.Selector.STREAM, stream1.streamName, stream1.streamFields[stream1.fieldIndex]);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                { STREAM.RESERVATIONS, 1, null, 0, JOINTYPE.LEFT, 6 },
                { STREAM.ORDERS, 1, null, 0, JOINTYPE.INNER, 5 },
                { STREAM.ORDERS, 1, null, 0, JOINTYPE.EMPTY_STRING_JOIN, RuntimeException.class },
                { STREAM.ORDERS, 1, null, 0, JOINTYPE.NOT_EXISTING_PRIOR, IllegalArgumentException.class },
                { STREAM.NULL, 1, null, 0, JOINTYPE.EMPTY, NullPointerException.class },
                { STREAM.ORDERS, 1, null, 0, JOINTYPE.SAME_STREAM_JOIN, IllegalArgumentException.class },
                { STREAM.RESERVATIONS, 1, null, 0, JOINTYPE.WRONG_LEFT, 6 },
                { STREAM.EMPTY, 0, null, 0, JOINTYPE.WRONG_LEFT, NullPointerException.class },
        });
    }

//    @Parameterized.Parameters
//    public static Collection<Object[]> data() {
//        return Arrays.asList(new Object[][] {
//                { STREAM.RESERVATIONS, 1, STREAM.ORDERS, 0, JOINTYPE.LEFT, 6 },
//                { STREAM.ORDERS, 1, STREAM.EMPTY, 0, JOINTYPE.INNER, 5 },
//                { STREAM.ORDERS, 1, STREAM.NULL, 1, JOINTYPE.EMPTY_STRING_JOIN, RuntimeException.class },
//                { STREAM.ORDERS, 1, STREAM.ORDERS, 1, JOINTYPE.NOT_EXISTING_PRIOR, IllegalArgumentException.class },
//                { STREAM.NULL, 1, STREAM.RESERVATIONS, 1, JOINTYPE.EMPTY, NullPointerException.class },
//                { STREAM.ORDERS, 1, STREAM.ORDERS, 1, JOINTYPE.SAME_STREAM_JOIN, IllegalArgumentException.class },
//                { STREAM.RESERVATIONS, 1, STREAM.ORDERS, 1, JOINTYPE.WRONG_LEFT, 6 },
//                { STREAM.EMPTY, 0, STREAM.RESERVATIONS, 1, JOINTYPE.WRONG_LEFT, NullPointerException.class },
//        });
//    }

    @Test
    public void testJoinBoltJoin() {
        // JoinBolt j1, j2;
        //stream2 = StreamGenerator.createStream(STREAM.RESERVATIONS, 0);

        switch (jointype) {
            case LEFT:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.leftJoin(stream2.streamName, stream2.streamFields[stream2.fieldIndex], stream1.streamName);
                            //.leftJoin(stream3.streamName, stream3.streamFields[stream3.fieldIndex], stream1.streamName);
                    bolt.select(stream1.commaSeparatedValues + stream2.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    int actualSize = mockedCollector.outputs.size();
                    assertEquals((int) expectedResult, actualSize); // Cast a int
                    // assertTrue(Objects.equals(j1.getClass(), JoinBolt.class) && Objects.equals(j2.getClass(), JoinBolt.class)); // ADD
                    // assertTrue(j1 instanceof JoinBolt && j2 instanceof JoinBolt);
                } catch (Exception e) {
                    assertTrue(e instanceof ClassCastException);
                    //assertEquals(expectedResult.getClass().getName(), e.getClass().getName());
                }
                break;

            case WRONG_LEFT:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.leftJoin(stream2.streamName, "foo", stream1.streamName);
                    bolt.select(stream1.commaSeparatedValues + stream2.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    assertEquals(expectedResult, mockedCollector.outputs.size());
                } catch (Exception e) {
                    assertEquals(expectedResult, e.getClass());
                }
                break;

            case INNER:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.join(stream2.streamName, stream2.streamFields[stream2.fieldIndex], stream1.streamName);
                            //.join(stream3.streamName, stream3.streamFields[stream3.fieldIndex], stream1.streamName);
                    bolt.select(stream1.commaSeparatedValues + stream2.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    int actualSize = mockedCollector.outputs.size();
                    expectedResult = ClassCastException.class;
                    assertEquals((int) expectedResult, actualSize); // Cast a int
                    //assertTrue(Objects.equals(j1.getClass(), JoinBolt.class) && Objects.equals(j2.getClass(), JoinBolt.class)); // ADD
                } catch (Exception e) {
                    assertTrue(e instanceof ClassCastException);
                    //assertEquals(expectedResult.getClass().getName(), e.getClass().getName());
                }
                break;


            case EMPTY_STRING_JOIN:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.join("", "", stream1.streamName);
                    bolt.select(stream1.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    assertEquals(expectedResult, mockedCollector.outputs.size());
                } catch (Exception e) {
                    assertEquals(expectedResult, e.getClass());
                }
                break;

            case SAME_STREAM_JOIN:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.join(stream2.streamName, stream2.streamFields[stream2.fieldIndex], stream1.streamName);
                    bolt.join(stream2.streamName, stream2.streamFields[stream2.fieldIndex], stream1.streamName);

                    bolt.select(stream1.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    assertEquals(expectedResult, mockedCollector.outputs.size());
                } catch (Exception e) {
                    assertEquals(expectedResult, e.getClass());
                }
                break;

            case NOT_EXISTING_PRIOR:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.join(stream2.streamName, stream2.streamFields[stream2.fieldIndex], "foo");

                    bolt.select(stream1.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    assertEquals(expectedResult, mockedCollector.outputs.size());
                } catch (Exception e) {
                    assertEquals(expectedResult, e.getClass());
                }
                break;

            case EMPTY:
                try {
                    TupleWindow window = createNewWindow(stream1.inputStream, stream2.inputStream);

                    bolt.join(null, stream2.streamFields[stream2.fieldIndex], stream1.streamName);

                    bolt.select(stream1.commaSeparatedValues + stream2.commaSeparatedValues);
                    bolt.prepare(null, null, mockedCollector);
                    bolt.execute(window);

                    assertEquals(expectedResult, mockedCollector.outputs.size());
                } catch (Exception e) {
                    assertEquals(expectedResult, e.getClass());
                }
                break;

            default:
                break;
        }
    }

    private static ArrayList<Tuple> createNewStream(String streamName, String[] fieldNames, Object[][] data, String srcComponentName) {
        GeneralTopologyContext mockContext = new CustomContext(fieldNames);
        ArrayList<Tuple> stream = new ArrayList<>();
        for (Object[] entry : data) {
            TupleImpl rec = new TupleImpl(mockContext, Arrays.asList(entry), srcComponentName, 0, streamName);
            stream.add(rec);
        }
        return stream;
    }

    private TupleWindow createNewWindow(ArrayList<Tuple>... streams) {
        if (streams == null)
            return new TupleWindowImpl(new ArrayList<Tuple>(), null, null);
        ArrayList<Tuple> allStreams = new ArrayList<>();
        for (int i = 0; i < streams.length; i++) {
            allStreams.addAll(streams[i]);
        }
        return new TupleWindowImpl(allStreams, null, null);
    }

    public enum JOINTYPE {
        LEFT, WRONG_LEFT, INNER, EMPTY_STRING_JOIN, SAME_STREAM_JOIN, NOT_EXISTING_PRIOR, EMPTY
    }

    public enum STREAM {
        RESERVATIONS, MENU, ORDERS, NULL, EMPTY
    }

    public static class StreamGenerator {

        public static TupleStream createStream(STREAM stream, int fieldIndex) {
            switch (stream) {
                case RESERVATIONS:
                    return new ReservationStream(fieldIndex);
                case MENU:
                    return new MenuStream(fieldIndex);
                case ORDERS:
                    return new OrderStream(fieldIndex);
                case NULL:
                    return new TupleStreamImpl();
                case EMPTY:
                    return new EmptyStream(fieldIndex);
                default:
                    return new TupleStreamImpl();
            }
        }

        public static abstract class TupleStream {
            public ArrayList<Tuple> inputStream = null;
            public String[] streamFields = null;
            public Object[][] streamData = null;
            public String commaSeparatedValues = null;
            public String streamName = null;
            public int fieldIndex = 0;

            public TupleStream() {
            }

            public TupleStream(String[] streamFields, Object[][] streamData) {
                this.streamFields = streamFields;
                this.streamData = streamData;
                for (int i = 0; i < streamFields.length; i++) {
                    this.commaSeparatedValues += streamFields[i];
                }
            }
        }

        public static class TupleStreamImpl extends TupleStream {
            public TupleStreamImpl() {
                super();
            }
        }

        public static class ReservationStream extends TupleStream {
            public ReservationStream(int field1Index) {
                super(reservationsFields, reservations);
                this.fieldIndex = field1Index;
                this.streamName = "reservations";
                this.inputStream = createNewStream("reservations", this.streamFields, this.streamData,
                        "reservationsSpout");
            }
        }

        public static class MenuStream extends TupleStream {
            public MenuStream(int field1Index) {
                super(menuFields, menu);
                this.fieldIndex = field1Index;
                this.streamName = "menu";
                this.inputStream = createNewStream("menu", this.streamFields, this.streamData, "menuSpout");
            }
        }

        public static class OrderStream extends TupleStream {
            public OrderStream(int field1Index) {
                super(ordersFields, orders);
                this.fieldIndex = field1Index;
                this.streamName = "orders";
                this.inputStream = createNewStream("orders", this.streamFields, this.streamData, "ordersSpout");
            }
        }

        public static class EmptyStream extends TupleStream {
            public EmptyStream(int field1Index) {
                super(emptyFields, empty);
                this.fieldIndex = field1Index;
                this.streamName = null;
                this.inputStream = createNewStream(null, this.streamFields, this.streamData, "emptySpout");
            }
        }
    }

}

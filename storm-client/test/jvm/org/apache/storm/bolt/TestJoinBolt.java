package org.apache.storm.bolt;

import static org.junit.Assert.assertEquals;

import org.apache.storm.bolt.JoinBolt;
import org.apache.storm.bolt.JoinBolt.Selector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TestJoinBolt {

    private Selector type;
    private String srcOrStreamId;
    private String fieldName;
    private Class<?> expectedResult;

    public TestJoinBolt(Selector type, String srcOrStreamId, String fieldName, Class<?> expectedResult) {
        this.type = type;
        this.srcOrStreamId = srcOrStreamId;
        this.fieldName = fieldName;
        this.expectedResult = expectedResult;
    }

    @Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                { JoinBolt.Selector.STREAM, "", "", JoinBolt.class },
                { null, "streamName", "keyField", JoinBolt.class },
                { JoinBolt.Selector.STREAM, null, null, NullPointerException.class },
                { JoinBolt.Selector.SOURCE, "streamName", "keyField", JoinBolt.class },
                { JoinBolt.Selector.STREAM, "streamName", "keyField", JoinBolt.class }
        });
    }

    @Test
    public void testJoinBolt() {
        try {
            JoinBolt joiner = new JoinBolt(type, srcOrStreamId, fieldName);
            assertEquals(expectedResult, joiner.getClass());
        } catch (Exception e) {
            assertEquals(expectedResult, e.getClass());
        }
    }
}

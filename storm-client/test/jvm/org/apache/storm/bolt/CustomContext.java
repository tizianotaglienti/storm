package org.apache.storm.bolt;
import org.apache.storm.task.GeneralTopologyContext;
import org.apache.storm.tuple.Fields;
import java.util.HashMap;

public class CustomContext extends GeneralTopologyContext{
    private Fields outputFields;

    public CustomContext(String[] fieldNames) {
        super(null, new HashMap<>(), null, null, null, null);
        this.outputFields = new Fields(fieldNames);
    }

    @Override
    public String getComponentId(int taskId) {
        return "foo";
    }

    @Override
    public Fields getComponentOutputFields(String componentId, String streamId) {
        return outputFields;
    }
}
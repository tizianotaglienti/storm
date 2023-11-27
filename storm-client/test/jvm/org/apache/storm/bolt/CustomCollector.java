package org.apache.storm.bolt;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Tuple;

public class CustomCollector extends OutputCollector{
    public ArrayList<List<Object>> outputs = new ArrayList<>();

    public CustomCollector() {
        super(null);
    }

    @Override
    public List<Integer> emit(Collection<Tuple> anchors, List<Object> tuple) {
        outputs.add(tuple);
        return null;
    }
}
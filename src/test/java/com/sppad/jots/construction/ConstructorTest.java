package com.sppad.jots.construction;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Set;

import org.junit.Test;
import org.snmp4j.smi.VariableBinding;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sppad.jots.annotations.Jots;
import com.sppad.jots.constructor.SnmpTree;

public class ConstructorTest
{
  public Function<Field, String> getFieldName = new Function<Field, String>()
  {
    public String apply(Field field)
    {
      return field.getName();
    }
  };

  @SuppressWarnings("unused")
  private class ParentClass
  {
    public String name = "foobar";
  }

  @SuppressWarnings("unused")
  private class TestObject extends ParentClass
  {
    public boolean bool;

    @Jots(cls = CollectionObject.class)
    public final Set<CollectionObject> collection = Sets.newHashSet(
        new CollectionObject(), new CollectionObject(), new CollectionObject());
    public final Set<CollectionObject> nonAnnotatedCollection = Sets
        .newHashSet();;
    public final NestedObject obj = new NestedObject();
  }

  @SuppressWarnings("unused")
  private class NestedObject
  {
    public int number;
  }

  @SuppressWarnings("unused")
  private class CollectionObject
  {
    @Jots(cls = NestedObject.class)
    public final Collection<NestedObject> nestedTable = Lists.newArrayList();
    public float floatingPoint;
  }

  @Test
  public void testCreate()
  {
    // Node node = NodeTreeConstructor.createTree(TestObject.class);
    // Map<Node, IntStack> staticOidMap = OidGenerator.getStaticOidParts(node);
    //
    // MibGenerator.createMib(new int[] {}, node, staticOidMap);
    //
    // TreeConstructor.create(staticOidMap, node, new TestObject());

    final Object obj = new TestObject();

    SnmpTree tree = TreeBuilder.from(obj).prefix(new int[] { 1, 3, 6, 1, 4, 1, 100 }).build();
    
    for(VariableBinding vb : tree)
    {
      System.out.println(vb.toString());
    }
  }

}

package com.sppad.snmp.constructor;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.snmp4j.smi.OID;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sppad.common.object.ObjUtils;
import com.sppad.datastructures.primative.IntStack;
import com.sppad.datastructures.primative.RefStack;
import com.sppad.snmp.JotsOID;
import com.sppad.snmp.annotations.SnmpName;
import com.sppad.snmp.annotations.SnmpNotSettable;
import com.sppad.snmp.constructor.handlers.CollectionHandler;
import com.sppad.snmp.constructor.handlers.DefaultHandler;
import com.sppad.snmp.constructor.handlers.MapHandler;
import com.sppad.snmp.constructor.handlers.NullHandler;
import com.sppad.snmp.constructor.handlers.ObjectHandler;
import com.sppad.snmp.constructor.mib.MibConstructor;
import com.sppad.snmp.constructor.mib.SnmpDescription;
import com.sppad.snmp.util.SnmpUtils;

/**
 * 
 * Creates an SNMP tree object allowing for gets and sets on any fields
 * belonging to the specified object or any object referenced through that
 * object.
 * <p>
 * Values that can be get/set are Strings, primitives and their corresponding
 * classes (e.g. Integer). Collections and Maps are also traversed to add the
 * objects they contain through the tree.
 * <p>
 * Each time an object is descended into, the OID increases in length, creating
 * a subtree starting with ".1". On each next field of an object, the last part
 * of the OID is incremented by one. When a Collection or Map is encountered,
 * the OID gets a level of dynamic OID added per level of Collection or Map
 * encountered.
 * <p>
 * Since SNMP does not allow for tables within entries, multi-indexed tables are
 * generated whenever a nested table is encountered.
 * 
 * 
 * <pre>
 * 
 * For example, the transformation may look like:
 * 
 * .1 (Table of Foo)
 * .1.1 (FooEntry)
 * .1.1.1.1001 (Foo.firstField, index = 1001)
 * .1.1.2.1001 (Table of Bar)
 * .1.1.2.1.1001.5001 (Bar.firstField index = 5001)
 * 
 * transformed to:
 * 
 * .1 (Table of Foo)
 * .1.1 (FooEntry)
 * .1.1.1001 (Foo.firstField, index = 1001)
 * .1.2 (Table of Bar)
 * .1.2.1 (BarEntry)
 * .1.2.1.1.1001.5001 (Bar.firstField key = index = 1001, 5001)
 * </pre>
 * 
 * @author sepand
 * 
 */
public class SnmpTreeConstructor
{
  private class FieldInfo
  {
    String description;
    Field field;
    boolean isSimple;
    boolean isTableType;
    List<IntStack> oidVisitedMap = new LinkedList<IntStack>();
    Method setter;
    String snmpName;

    public FieldInfo(Field field)
    {
      isSimple = SnmpUtils.isSimple(field.getType());
      isTableType = isTableType(field.getType());
      setter = getSetterMethod(field, field.getDeclaringClass());
      this.field = field;

      SnmpName snmpNameObj = field.getAnnotation(SnmpName.class);
      if (snmpNameObj == null)
        snmpName = field.getName();
      else
        snmpName = snmpNameObj.value();

      if (createMib)
        description = SnmpDescription.getDescription(field);
    }

    public boolean isWritable()
    {
      return setter != null;
    }
  }

  /** Gets / caches class info for a particular class */
  private LoadingCache<Class<?>, ClassInfo> classInfoCache = CacheBuilder
      .newBuilder().maximumSize(1000)
      .build(new CacheLoader<Class<?>, ClassInfo>()
      {
        @Override
        public ClassInfo load(Class<?> key)
        {
          return new ClassInfo(key);
        }
      });

  /** Gets / caches class info for a particular field */
  private LoadingCache<Field, FieldInfo> fieldInfoCache = CacheBuilder
      .newBuilder().maximumSize(1000).build(new CacheLoader<Field, FieldInfo>()
      {
        @Override
        public FieldInfo load(Field key)
        {
          return new FieldInfo(key);
        }
      });

  /** Gets / caches the handler for a given class */
  private LoadingCache<Class<?>, ObjectHandler> objectHandlerCache = CacheBuilder
      .newBuilder().maximumSize(1000)
      .build(new CacheLoader<Class<?>, ObjectHandler>()
      {
        @Override
        public ObjectHandler load(Class<?> key)
        {
          return handlers.get(getFirstHandledClass(key));
        }

        public Class<?> getFirstHandledClass(Class<?> klass)
        {
          // check if the class is already handled
          if (handlers.containsKey(klass))
            return klass;

          // need to check all interfaces to see if they are handled
          for (Class<?> interfaceClass : klass.getInterfaces())
            if (handlers.containsKey(interfaceClass))
              return interfaceClass;

          // next, need to check all super classes
          while ((klass = klass.getSuperclass()) != null)
            if (handlers.containsKey(klass))
              return klass;

          // nothing matches, just handle it as an object
          return Object.class;
        }
      });

  /** Whether or not to create a MIB file while creating the tree */
  private final boolean createMib;

  /** Handlers all non-table, non-basic objects */
  private final DefaultHandler defaultHandler = new DefaultHandler();

  /**
   * Maps a type to a class implementing how to handle that type while
   * descending
   */
  private final Map<Type, ObjectHandler> handlers = new HashMap<Type, ObjectHandler>();

  /** Used for constructing a MIB, if requested */
  private MibConstructor mc;

  /** Used for building the MIB name for the current oid */
  private final Deque<String> nameStack = new ArrayDeque<String>();

  /** Handlers null objects */
  private final NullHandler nullHandler = new NullHandler();

  /** Used to detect cycles by checking for visited objects. */
  private final RefStack<Object> objectHandleStack = new RefStack<Object>();

  /** Keeps track of length of extensions on extension stack */
  private final IntStack oidExtLenStack = new IntStack();

  /** Keeps track of snmp oid extensions for tables */
  private final IntStack oidExtStack = new IntStack();

  /** Keeps track of snmp oid */
  private final IntStack oidStack = new IntStack();

  /** The table prefix, added to each OID before creating */
  private final int[] prefix;

  /**
   * Saves the OID stack for restoring after exiting a table. Used for nested
   * tables, since the OID needs to be modified.
   */
  private final Map<String, IntStack> savedOidStackMap = new HashMap<String, IntStack>();

  /** Object representing the snmp tree for doing lookups and sets */
  private final SnmpTreeSkeleton snmpTreeSkeleton;

  /**
   * Maps OID path to what OID in the parent they belong to. For dealing with
   * nested tables.
   */
  private final Map<String, Integer> subTableOidModMap = new HashMap<String, Integer>();

  /** Stack of the current table. */
  private final Deque<String> tableNameStack = new ArrayDeque<String>();

  /** What index in the OID stack corresponds to the parent table */
  private final IntStack tableOidIndexStack = new IntStack(10);

  /**
   * Creates an SnmpTree by descending starting with the given object.
   * 
   * @param prefix
   *          The OID prefix to use for the tree
   * @param obj
   *          The object to base the tree off of
   * @return The constructed SnmpTree
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   * @throws InvocationTargetException
   * 
   * @see SnmpTree
   */
  public static SnmpTree createSnmpTree(int[] prefix, Object obj)
      throws IllegalAccessException, IllegalArgumentException,
      InvocationTargetException
  {
    SnmpTreeConstructor stc = new SnmpTreeConstructor(prefix, obj);
    return stc.snmpTreeSkeleton.finishTreeConstruction();
  }

  public static SnmpTree createSnmpTree(String mibName, String rootName,
      int[] prefix, Object obj, OutputStream mibOutputStream)
      throws IllegalAccessException, IllegalArgumentException,
      InvocationTargetException, IOException
  {
    Preconditions.checkNotNull(mibOutputStream);

    SnmpTreeConstructor stc = new SnmpTreeConstructor(mibName, rootName,
        prefix, obj, mibOutputStream);
    return stc.snmpTreeSkeleton.finishTreeConstruction();
  }

  private static Method getSetterMethod(Field field, Class<?> klass)
  {
    try
    {
      if (field.getAnnotation(SnmpNotSettable.class) != null)
        return null;

      String setterName = SnmpUtils.getSetterName(field.getName());
      Class<?> currentClass = klass;
      while (currentClass != Object.class)
      {
        try
        {
          return currentClass.getDeclaredMethod(setterName, field.getType());
        }
        catch (NoSuchMethodException e)
        {
          currentClass = currentClass.getSuperclass();
        }
      }
    }
    catch (SecurityException e)
    {
      throw new RuntimeException(
          "Exception while checking if field is writable: ", e);
    }

    return null;
  }

  /** Sets up initial handler classes. */
  {
    // collections types
    handlers.put(Map.class, new MapHandler());
    handlers.put(List.class, new CollectionHandler());
    handlers.put(Collection.class, new CollectionHandler());

    handlers.put(ImmutableMap.class, new MapHandler());
    handlers.put(ImmutableList.class, new CollectionHandler());
    handlers.put(ImmutableCollection.class, new CollectionHandler());

    // general object
    handlers.put(Object.class, defaultHandler);
  }

  /**
   * Private constructor to force using {@link #createSnmpTree(int[], Object)}
   * 
   * @throws InvocationTargetException
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   */
  private SnmpTreeConstructor(int[] prefix, Object obj)
      throws IllegalArgumentException, IllegalAccessException,
      InvocationTargetException
  {
    this.createMib = false;

    this.prefix = prefix;
    this.oidStack.push(1);
    this.tableOidIndexStack.push(1);
    this.snmpTreeSkeleton = new SnmpTreeSkeleton(prefix);

    this.descend(obj, obj.getClass(), null);

  }

  private SnmpTreeConstructor(String mibName, String rootName, int[] prefix,
      Object obj, OutputStream mibOutputStream)
      throws IllegalArgumentException, IllegalAccessException,
      InvocationTargetException, IOException
  {
    this.createMib = true;

    this.prefix = prefix;
    this.oidStack.push(1);
    this.tableOidIndexStack.push(1);
    this.snmpTreeSkeleton = new SnmpTreeSkeleton(prefix);

    this.mc = new MibConstructor(mibName, rootName, "enterprises", 15001,
        mibOutputStream);
    this.descend(obj, obj.getClass(), null);
    this.mc.finish();
  }

  public void descend(Object obj, Class<?> baseClass, Field field)
      throws IllegalArgumentException, IllegalAccessException,
      InvocationTargetException
  {
    if (obj == null)
    {
      nullHandler.handle(this, obj, field);
    }
    else if (obj.getClass().isAnonymousClass()
        && objectHandleStack.contains(obj))
    {
      // prevent reference to outer class from causing a loop
    }
    else if (isTableType(obj.getClass()))
    {
      getHandler(obj.getClass()).handle(this, obj, field);
    }
    else
    {
      checkForCircularReference(obj);
      onEnter(obj, field);

      Collection<Field> objectFields = getClassInfo(baseClass).fields;
      for (final Field objectField : objectFields)
        descendIntoField(obj, objectField);

      onExit(obj, field);
    }
  }

  public void onCollectionEnter(Object obj, Field field, Class<?> keyType)
  {
    FieldInfo info = getFieldInfo(field);

    nameStack.push(info.snmpName);
    tableOidIndexStack.push(oidStack.size());

    preModifiyOidForTable(info);
    tableNameStack.push(info.snmpName);

    if (createMib && info.isTableType)
      addToMib(info, keyType, null);

    oidStack.push(1);
    oidExtStack.push(0);
    oidExtLenStack.push(1);
  }

  public void onCollectionExit(Object obj, Field field)
  {
    FieldInfo info = getFieldInfo(field);

    int count = oidExtLenStack.pop();
    oidExtStack.remove(count);
    oidStack.pop();

    tableNameStack.pop();

    postModifiyOidForTable(info);

    tableOidIndexStack.pop();
    nameStack.pop();
  }

  public void onNextCollectionValue(Object obj, Object collectionIndex)
  {
    // remove extension by removing all ints for that extension
    // the oidExtLenStack lets us know how many entries to remove
    oidExtStack.remove(oidExtLenStack.pop());

    // if there is no specified snmp index for the given type, use the
    // collection index passed in to find out the type
    Object extensionObject = getSnmpIndex(obj);
    if (extensionObject == null)
      extensionObject = collectionIndex;

    // add the extension (dynamic oid part) to the extension stack
    int[] extension = SnmpUtils.getSnmpExtension(extensionObject);
    oidExtLenStack.push(extension.length);
    oidExtStack.copyFrom(extension, 0, extension.length);
  }

  protected String buildStringPath(Deque<String> names, boolean includeLast)
  {
    final Iterator<String> nameIter = names.descendingIterator();

    StringBuilder builder = new StringBuilder();

    while (nameIter.hasNext())
    {
      String nextName = nameIter.next();
      if (!includeLast && !nameIter.hasNext())
        break;

      int lastNameStartIndex = builder.length();
      builder.append(nextName);

      char firstLetter = Character.toUpperCase(builder
          .charAt(lastNameStartIndex));
      builder.setCharAt(lastNameStartIndex, firstLetter);
    }

    return builder.toString();
  }

  protected void descendIntoField(Object obj, Field field)
      throws IllegalArgumentException, IllegalAccessException,
      InvocationTargetException
  {
    field.setAccessible(true);

    onNextField(obj, field);

    Object nextObject = field.get(obj);
    if (nextObject == null)
      nullHandler.handle(this, obj, field);
    else if (!getFieldInfo(field).isSimple)
      getHandler(field.getType()).handle(this, nextObject, field);
  }

  /**
   * Creates a String representation of the current object traversal stack.
   * 
   * @return A String containing the object stack, not including the current
   *         object.
   */
  protected String getObjectHandleStack()
  {
    StringBuilder stackBuilder = new StringBuilder();
    for (Object stackObject : objectHandleStack.values())
    {
      stackBuilder.append("\n    ");
      stackBuilder.append(ObjUtils.getRefInfo(stackObject));
    }

    return stackBuilder.toString();
  }

  protected Object getSnmpIndex(Object obj)
  {

    try
    {
      ClassInfo info = getClassInfo(obj.getClass());
      Field extensionField = info.extensionField;
      if (info.extensionField != null)
      {
        extensionField.setAccessible(true);
        return extensionField.get(obj);
      }
    }
    catch (IllegalArgumentException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    catch (IllegalAccessException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return null;
  }

  protected boolean isTableType(Class<?> klass)
  {
    return getHandler(klass) != defaultHandler;
  }

  protected void onEnter(Object obj, Field field)
  {
    if (field != null && !getFieldInfo(field).isTableType)
    {
      nameStack.push(field.getName());
      // if (createMib)
      // inTableNameStackStack.peek().push(field.getName());
    }

    objectHandleStack.push(obj);
    oidStack.push(0);
  }

  protected void onExit(Object obj, Field field)
  {
    oidStack.pop();
    objectHandleStack.pop();

    if (field != null && !getFieldInfo(field).isTableType)
    {
      // if (createMib)
      // inTableNameStackStack.peek().pop();
      nameStack.pop();
    }
  }

  protected void onNextField(Object obj, Field field)
  {
    FieldInfo info = getFieldInfo(field);
    nameStack.push(info.snmpName);

    // increment current oid by one if not a table
    // a table won't take up an OID in the current subtree
    if (!info.isTableType)
      oidStack.push(oidStack.pop() + 1);

    if (createMib && !info.isTableType)
      addToMib(info, null, obj.getClass());

    if (info.isSimple)
      addToSnmpTable(obj, info);

    nameStack.pop();
  }

  protected void postModifiyOidForTable(FieldInfo info)
  {
    String parent = tableNameStack.peek();
    String name = parent + "." + nameStack.peek();
    oidStack.clear();
    oidStack.copyFrom(savedOidStackMap.get(name));
  }

  protected void preModifiyOidForTable(FieldInfo info)
  {
    String parent = tableNameStack.peek();
    String name = parent + "." + nameStack.peek();

    // TODO - this shouldn't be based off name, but rather OID?
    Integer oid = subTableOidModMap.get(name);
    if (oid == null)
    {
      oid = subTableOidModMap.get(parent);
      if (oid == null)
        oid = oidStack.get(1);

      oid++;

      subTableOidModMap.put(parent, oid);
      subTableOidModMap.put(name, oid);
      savedOidStackMap.put(name, new IntStack(oidStack));
    }

    int index = tableOidIndexStack.get(tableOidIndexStack.size() - 2);
    int count = oidStack.size() - index + 1;

    oidStack.remove(count);
    oidStack.push(oid);
  }

  private void addToMib(FieldInfo info, Class<?> keyType,
      Class<?> enclosingClass)
  {
    if (!info.oidVisitedMap.contains(oidStack))
    {
      int oid = oidStack.peek();
      // check greater than 1 because the current table is in the name
      // stack
      boolean inTable = (tableNameStack.size() > 1);

      String name = buildStringPath(nameStack, true);
      String parent = buildStringPath(nameStack, false);

      if (info.isTableType)
        mc.addTable(parent, name, oid, inTable, "", keyType);
      else if (info.isSimple)
        mc.addItem(parent, name, oid, info.field.getType(), info.description,
            info.isWritable());
      else
        mc.addEntry(parent, name, oid, "");

      info.oidVisitedMap.add(new IntStack(oidStack));
    }
  }

  private void addToSnmpTable(Object obj, FieldInfo info)
  {
    OID oid = new JotsOID(prefix, oidStack, oidExtStack);
    snmpTreeSkeleton.add(oid, info.field, obj, info.setter);
  }

  /**
   * Checks to see if descending into the specified object will cause a circular
   * loop to occur.
   * 
   * @param obj
   *          The object to check for.
   * @throws CircularReferenceException
   *           if a circular reference is encountered.
   */
  private void checkForCircularReference(Object obj)
  {
    // check for circular reference: if the stack has the current object,
    // then we visited it on the way down
    if (objectHandleStack.contains(obj))
    {
      String exceptionString = "Cannot handle circular references. Can make fields with circular references transient to skip.";
      exceptionString += "\nOn object: " + ObjUtils.getRefInfo(obj);
      exceptionString += "\nStack:" + getObjectHandleStack();

      throw new CircularReferenceException(exceptionString);
    }
  }

  private ClassInfo getClassInfo(Class<?> klass)
  {
    try
    {
      return classInfoCache.get(klass);
    }
    catch (ExecutionException e)
    {
      throw new RuntimeException(e);
    }
  }

  private FieldInfo getFieldInfo(Field field)
  {
    try
    {
      return fieldInfoCache.get(field);
    }
    catch (ExecutionException e)
    {
      throw new RuntimeException(e);
    }
  }

  // For some reason, performance is much better when using this function
  private ObjectHandler getHandler(Class<?> klass)
  {
    try
    {
      return objectHandlerCache.get(klass);
    }
    catch (ExecutionException e)
    {
      throw new RuntimeException(e);
    }
  }
}
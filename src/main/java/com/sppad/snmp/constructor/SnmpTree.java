package com.sppad.snmp.constructor;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;

import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.google.common.base.Preconditions;
import com.sppad.datastructures.primative.IntStack;
import com.sppad.snmp.exceptions.SnmpNoMoreEntriesException;
import com.sppad.snmp.exceptions.SnmpNotWritableException;
import com.sppad.snmp.exceptions.SnmpOidNotFoundException;
import com.sppad.snmp.exceptions.SnmpPastEndOfTreeException;
import com.sppad.snmp.lookup.SnmpLookupField;

/**
 * 
 * 
 * @author sepand
 * @see SnmpTreeConstructor
 */
public class SnmpTree
{
    /** The last valid index */
    public final int lastIndex;

    /** Used for BulkGet, since the index of the next entry is known */
    private SnmpLookupField[] fieldArray;

    /** The prefix used to create the OIDs in the tree */
    private final int[] prefix;

    /** Creates a VariableBinding object for returning OID, value pairs */
    public static VariableBinding createVarBind(OID oid, Object object)
    {
	Variable variable;
	if (object instanceof Integer)
	    variable = new Integer32((Integer) object);
	else
	    variable = new OctetString(object == null ? "" : object.toString());

	return new VariableBinding(oid, variable);
    }

    protected SnmpTree(int[] prefix, SnmpLookupField[] fieldArray)
    {
	this.prefix = prefix;
	this.lastIndex = fieldArray.length - 1;
	this.fieldArray = fieldArray;
    }

    /**
     * @param prefix
     *            The prefix used to create the OIDs in the tree.
     * @param snmpFields
     *            A sorted set of the SnmpLookupField objects that make up the
     *            tree.
     */
    protected SnmpTree(int[] prefix, SortedSet<SnmpLookupField> snmpFields)
    {
	this(prefix, snmpFields.toArray(new SnmpLookupField[snmpFields.size()]));
    }

    public VariableBinding get(int index)
    {
	SnmpLookupField field = getBackingField(index);
	return createVarBind(field.getOid(), field.get());
    }

    public SnmpLookupField getBackingField(int index)
    {
	Preconditions.checkArgument(index >= 0, "Index cannot be negative");

	if (index > lastIndex)
	    throw new SnmpNoMoreEntriesException(
		    "There are no more values in this MIB");

	return fieldArray[index];
    }

    public VariableBinding get(OID oid)
    {
	return createVarBind(oid, getValue(getIndex(oid)));
    }

    /**
     * Gets the annotation for the source field represented by the given index.
     * 
     * @param index
     *            The index of the field
     * @param annotationClass
     *            The annotation class to get.
     * 
     * @return The annotation if it exists, null otherwise.
     */
    public Annotation getAnnotation(int index, Class<?> annotationClass)
    {
	return fieldArray[index].getAnnotation(annotationClass);
    }

    /**
     * Gets the next OID.
     * 
     * @param oid
     * @return
     */
    public VariableBinding getNext(OID oid)
    {
	return get(getNextIndex(oid));
    }

    public int getNextIndex(OID oid)
    {
	Preconditions.checkNotNull(oid);

	int index = Math.abs(getIndex(oid) + 1);
	if (index > lastIndex)
	    throw new SnmpPastEndOfTreeException(
		    "There are no more values in this MIB");

	return index;
    }

    /**
     * @return A copy of the prefix used to create this tree.
     */
    public int[] getPrefix()
    {
	return Arrays.copyOf(prefix, prefix.length);
    }

    public Object getValue(int index)
    {
	Preconditions.checkArgument(index >= 0, "Index cannot be negative");

	if (index > lastIndex)
	    throw new SnmpNoMoreEntriesException(
		    "There are no more values in this MIB");

	return fieldArray[index].get();
    }

    /**
     * Merges two SnmpTrees into 1 tree. If both trees contain the same OID,
     * then the OID is represented by the index from tree and not the current
     * object.
     * 
     * @param tree
     *            The tree to merge in.
     * @return An SnmpTree with fields from both the current object and tree
     */
    public SnmpTree mergeSnmpTrees(SnmpTree tree)
    {
	Preconditions.checkNotNull(tree, "tree must not be null");

	List<SnmpLookupField> fields = new ArrayList<SnmpLookupField>(
		this.lastIndex);

	int indexOne = 0;
	int indexTwo = 0;
	// merge trees by comparing each field pairwise and inserting from
	// either the first or the second tree
	while (indexOne <= tree.lastIndex && indexTwo <= this.lastIndex)
	{
	    int compareValue = tree.fieldArray[indexOne]
		    .compareTo(this.fieldArray[indexTwo]);

	    if (compareValue == 0)
	    {
		fields.add(tree.fieldArray[indexOne++]);
		indexTwo++;
	    }
	    else if (compareValue < 0)
	    {
		fields.add(tree.fieldArray[indexOne++]);
	    }
	    else
	    {
		fields.add(this.fieldArray[indexTwo++]);
	    }
	}

	// copy any left over fields (should all be greater now)
	for (int i = indexOne; i <= tree.lastIndex; i++)
	    fields.add(tree.fieldArray[i]);
	for (int i = indexTwo; i <= this.lastIndex; i++)
	    fields.add(this.fieldArray[i]);

	return new SnmpTree(findCommonPrefix(this.prefix, tree.prefix),
		fields.toArray(new SnmpLookupField[fields.size()]));
    }

    /**
     * Finds the common prefix OID for the two given prefixes.
     * 
     * @param prefixOne
     * @param prefixTwo
     * @return An int array with the common prefix
     */
    private int[] findCommonPrefix(int[] prefixOne, int[] prefixTwo)
    {
	int minPrefixLength = Math.min(prefixOne.length, prefixTwo.length);
	IntStack prefix = new IntStack(minPrefixLength);

	// add all the OID parts that are the same
	for (int i = 0; i < minPrefixLength; i++)
	    if (prefixOne[i] != prefixTwo[i])
		break;
	    else
		prefix.push(prefixOne[i]);

	return prefix.toArray();
    }

    /**
     * Performs a set for the given OID.
     * 
     * @param oid
     * @param value
     */
    public void set(OID oid, String value)
    {
	set(oid, value, true);
    }

    /**
     * Performs a set for the given OID.
     * 
     * @param oid
     *            The OID to set
     * @param value
     *            The value to set
     * @param checkWritable
     *            Whether or not to check of the field is writable or force
     *            setting the value
     */
    public void set(OID oid, String value, boolean checkWritable)
    {
	Preconditions.checkNotNull(oid);

	SnmpLookupField field = getLookupField(oid);
	if (checkWritable && !field.isWritable())
	    throw new SnmpNotWritableException("Cannot write to this OID");

	field.set(value);
    }

    /**
     * Performs a binary search to get the oid index, if it exists. Code from
     * {@link Arrays#binarySearch(Object[], Object, java.util.Comparator)}.
     * Don't want to create a SnmpLookupField to perform a binary search, so
     * duplicating the code here.
     * 
     * @param oid
     *            The oid to lookup.
     * @return The index of the oid in the fieldArray if it exists, or -(index
     *         of next oid) if it doesn't.
     */
    private int getIndex(OID oid)
    {
	int low = 0;
	int high = fieldArray.length - 1;

	while (low <= high)
	{
	    int mid = (low + high) >>> 1;

	    int compare = fieldArray[mid].getOid().compareTo(oid);
	    if (compare < 0)
		low = mid + 1;
	    else if (compare > 0)
		high = mid - 1;
	    else
		return mid; // key found
	}
	return -(low + 1); // key not found.
    }

    /**
     * Gets the SnmpLookupField object for the specified OID, if it exists.
     * 
     * @param oid
     *            A non-null OID corresponding to a field in the tree
     * @return The SnmpLookupField object for that field
     * @throws SnmpOidNotFoundException
     *             if there is no entry for the OID
     */
    private SnmpLookupField getLookupField(OID oid)
    {
	int index = getIndex(oid);
	if (index < 0)
	    throw new SnmpOidNotFoundException("Oid not in table");

	return fieldArray[index];
    }
}

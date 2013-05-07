package com.sppad.jots.construction;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;

import javax.annotation.Nullable;

import com.sppad.jots.util.Fields;
import com.sppad.jots.util.Strings;

abstract class Node
{
	static boolean isLeaf(final Class<?> cls)
	{
		return Fields.isSimple(cls);
	}

	static boolean isTable(final Class<?> cls)
	{
		return Collection.class.isAssignableFrom(cls)
				|| Map.class.isAssignableFrom(cls);
	}

	/** The field that allows access to this */
	final Field field;

	/** Whether or not the node is within a SNMP table */
	final boolean inTable;

	/** The class that this node corresponds to */
	final Class<?> klass;

	/** The name for this node */
	final String name;

	/** The children nodes */
	final Collection<Node> nodes = new LinkedList<Node>();

	/** The parent of this node, usually the object containing this node. */
	final Node parent;

	/** The children nodes in snmp order */
	final Collection<Node> snmpNodes = new LinkedList<Node>();

	/**
	 * The parent node for traversing the mib. This is diffrent than parent
	 * since nested collections need to be flattened into a mult-indexed table.
	 */
	final Node snmpParent;

	Node(final Class<?> klass, @Nullable final Node parent,
			final boolean inTable, final String name,
			@Nullable final Field field)
	{
		this.klass = klass;
		this.parent = parent;
		this.snmpParent = getSnmpParentNode(parent);
		this.inTable = inTable;
		this.name = Strings.firstCharToUppercase(name);
		this.field = field;

		if (field != null)
			field.setAccessible(true);
	}

	/**
	 * Allows visiting the node using a hierarchical visitor pattern according
	 * to the SNMP ordering (nested collections in hierarchy flattened).
	 */
	abstract void accept(final INodeVisitor visitor);

	void addChild(final Node node)
	{
		nodes.add(node);
	}

	void addSnmpChild(final Node node)
	{
		snmpNodes.add(node);
	}

	Node getSnmpParentNode(final Node parent)
	{
		return parent;
	}
}

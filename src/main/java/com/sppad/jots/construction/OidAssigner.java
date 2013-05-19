package com.sppad.jots.construction;

import com.sppad.jots.construction.nodes.EntryNode;
import com.sppad.jots.construction.nodes.INodeVisitor;
import com.sppad.jots.construction.nodes.LeafNode;
import com.sppad.jots.construction.nodes.Node;
import com.sppad.jots.construction.nodes.RootNode;
import com.sppad.jots.construction.nodes.TableEntryNode;
import com.sppad.jots.construction.nodes.TableNode;
import com.sppad.jots.datastructures.primative.IntStack;

class OidAssigner
{
	private static class OidAssigningVisitor implements INodeVisitor
	{
		private final IntStack oidStack = new IntStack();

		private OidAssigningVisitor()
		{
			this.oidStack.push(0);
		}

		@Override
		public void visitEnter(final EntryNode node)
		{
			visitEnterNode(node);
		}

		@Override
		public void visitEnter(final LeafNode node)
		{
			visitEnterNode(node);
		}

		@Override
		public void visitEnter(final RootNode node)
		{
			visitEnterNode(node);
		}

		@Override
		public void visitEnter(final TableEntryNode node)
		{
			visitEnterNode(node);
		}

		@Override
		public void visitEnter(final TableNode node)
		{
			visitEnterNode(node);
		}

		private void visitEnterNode(final Node node)
		{
			oidStack.push(oidStack.pop() + 1);

			node.setProperty("OID", oidStack.toArray());

			oidStack.push(0);
		}

		@Override
		public void visitExit(final EntryNode node)
		{
			visitExitNode(node);
		}

		@Override
		public void visitExit(final LeafNode node)
		{
			visitExitNode(node);
		}

		@Override
		public void visitExit(final RootNode node)
		{
			visitExitNode(node);
		}

		@Override
		public void visitExit(final TableEntryNode node)
		{
			visitExitNode(node);
		}

		@Override
		public void visitExit(final TableNode node)
		{
			visitExitNode(node);
		}

		private void visitExitNode(final Node node)
		{
			oidStack.pop();
		}
	}

	static void tag(final RootNode node)
	{
		final OidAssigningVisitor visitor = new OidAssigningVisitor();
		node.accept(visitor);
	}

	private OidAssigner()
	{

	}
}
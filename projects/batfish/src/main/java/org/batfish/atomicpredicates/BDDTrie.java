package org.batfish.atomicpredicates;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.sf.javabdd.BDD;

/**
 * A trie of BDDs, representing disjoint sets of headerspaces (atomic predicates). It is inspired by
 * DDNF, with two key differences: it stores BDDs instead of ternary bitvectors, and the children of
 * each node are disjoint subsets of the parent (rather than possibly overlapping subsets as in
 * DDNF). The latter property makes this a tree, while DDNFs are DAGs.
 *
 * <p>The DDNF as a whole represents a set of atomic predicates -- a partition of the full
 * headerspace into headerspaces such that for each one, the network does not distinguish between
 * any to headers in that space.
 *
 * <p>Each node represents the headerspace described by its BDD minus the headerspaces of its
 * children.
 */
public class BDDTrie {
  public final class AtomicPredicate {
    public final int _id;
    public final BDD _bdd;

    AtomicPredicate(int id, BDD bdd) {
      _id = id;
      _bdd = bdd;
    }

    public int getId() {
      return _id;
    }

    public BDD getBDD() {
      return _bdd;
    }
  }

  interface Atoms {
    Stream<AtomicPredicate> atomEntries();
  }

  /*
   * An internal node has already been divided, so don't include child entries.
   */
  static class InternalNodeAtoms implements Atoms {
    private final Node _node;

    InternalNodeAtoms(Node node) {
      assert node._children == null || node._children.size() == 0;
      _node = node;
    }

    @Override
    public Stream<AtomicPredicate> atomEntries() {
      return _node.atomicPredicate();
    }
  }

  /*
   * A leaf node haven't been divided yet, but we know that once it is, all children will be
   * subsets. Use this when we want to include them all.
   */
  static class LeafNodeAtoms implements Atoms {
    private final Node _node;

    LeafNodeAtoms(Node node) {
      assert node._children.size() > 0;
      _node = node;
    }

    @Override
    public Stream<AtomicPredicate> atomEntries() {
      return _node.atomicPredicates();
    }
  }

  public static class BDDTrieException extends Exception {
    private static final long serialVersionUID = 0;

    private String _message;
    private List<Integer> _parentPath;
    private Integer _childIndex;

    BDDTrieException(String message, List<Integer> parentPath, Integer childIndex) {
      _message = message;
      _parentPath = ImmutableList.copyOf(parentPath);
      _childIndex = childIndex;
    }
  }

  private class Node {
    // the original space.
    final BDD _headerspace;
    // the negation of the original headerspace
    final Supplier<BDD> _notHeaderspace;
    // headerspace minus children headerspaces
    final Supplier<BDD> _headerspaceMinusChildren;

    final Supplier<Integer> _id;

    /** Invariant: For all i, _children[i]._headerspace is a strict subset of _headerspace. */
    final List<Node> _children;

    int _inserts = 0;

    Node(BDD headerspace) {
      this(headerspace, new ArrayList<>());
    }

    Node(BDD headerspace, List<Node> children) {
      _children = children;
      _headerspace = headerspace;
      _headerspaceMinusChildren = Suppliers.memoize(this::computeHeaderspaceMinusChildren);
      _notHeaderspace = Suppliers.memoize(_headerspace::not);
      _id = Suppliers.memoize(this::computeAtomicPredicateId);
      _inserts = 0;
    }

    private void computeIds() {
      _id.get();
      for (Node child : _children) {
        child.computeIds();
      }
    }

    private @Nullable Integer computeAtomicPredicateId() {
      BDD atomicPredicate = _headerspaceMinusChildren.get();
      if (atomicPredicate.isZero()) {
        return null;
      }

      Integer id = _atomicPredicates.size();
      _atomicPredicates.add(atomicPredicate);
      return id;
    }

    BDD computeHeaderspaceMinusChildren() {
      BDD result = _headerspace;
      for (Node child : _children) {
        result = result.and(child._notHeaderspace.get());
      }
      return result;
    }

    Stream<AtomicPredicate> atomicPredicate() {
      Integer id = _id.get();
      BDD atomicPredicate = _headerspaceMinusChildren.get();
      return id == null ? Stream.empty() : Stream.of(new AtomicPredicate(id, atomicPredicate));
    }

    Stream<AtomicPredicate> atomicPredicates() {
      return Streams.concat(atomicPredicate(), _children.stream().flatMap(Node::atomicPredicates));
    }

    void checkInvariants(List<Integer> path) throws BDDTrieException {
      BDD childrenHeaderspaces = _headerspace.getFactory().zero();
      for (int i = 0; i < _children.size(); i++) {
        BDD childHeaderspace = _children.get(i)._headerspace;
        if (!childHeaderspace.imp(_headerspace).isOne()) {
          throw new BDDTrieException(
              "child headerspace is not a subset of parent headerspace", path, i);
        }
        if (_headerspace.imp(childHeaderspace).isOne()) {
          throw new BDDTrieException("child headerspace is equal to parent headerspace", path, i);
        }
        if (!childHeaderspace.and(childrenHeaderspaces).isZero()) {
          throw new BDDTrieException(
              "child headerspace intersects with a sibling's headerspace", path, i);
        }
        childrenHeaderspaces = childrenHeaderspaces.or(childHeaderspace);
      }
    }

    Stream<Node> insert(BDD bdd) {
      // invariant: bdd is a subset of _headerspace
      assert !bdd.isZero() && bdd.imp(_headerspace).isOne();
      _inserts++;

      if (_headerspace.equals(bdd)) {
        return Stream.of(this);
      }

      List<Node> bddChildren = new LinkedList<>();
      List<Stream<Node>> nodeStreams = new LinkedList<>();

      for (Node child : _children) {
        if (bdd.equals(child._headerspace)) {
          nodeStreams.add(Stream.of(child));
          return nodeStreams.stream().flatMap(Function.identity());
        }
        BDD intersection = bdd.and(child._headerspace);
        if (intersection.isZero()) {
          continue;
        } else if (intersection.equals(child._headerspace)) {
          /*
           * child._headerspace is a subset of bdd.
           * We want to insert a new node between this and child, but bdd might not be the right
           * headerspace (it may intersect other _children). So we have to wait until we've checked
           * all the other children.
           */
          bddChildren.add(child);
          continue;
        } else if (intersection.equals(bdd)) {
          /*
           * bdd is a subset of child._headerspace.
           * since children headerspaces are disjoint, bddChildren must be empty;
           */
          assert bddChildren.isEmpty();

          // bdd is a strict subset of child._headerspace
          nodeStreams.add(child.insert(bdd));
          return nodeStreams.stream().flatMap(Function.identity());
        } else {
          /*
           * We know the intersection only intersects this child (because the children are
           * disjoint), so insert directly to it.
           */
          nodeStreams.add(child.insert(intersection));

          // bdd intersects child. split.
          BDD bddMinusIntersection = bdd.and(child._notHeaderspace.get());

          assert !bddMinusIntersection.isZero();

          /*
           * The bddMinusIntersection can only intersect children we haven't looked at yet, so continue
           * this scan with the nonIntersection instead of bdd.
           */
          bdd = bddMinusIntersection;

          /*
           * Either bddChildren is empty (i.e. bdd is not a superset of any previous children), or
           * every previous child that is a subset of bdd is also a subset of nonIntersection.
           */
          assert bddChildren
              .stream()
              .allMatch(bddChild -> bddChild._headerspace.imp(bddMinusIntersection).isOne());
        }
      }

      // bdd does not intersect any child headerspace. add a new child for it.
      Node node;
      _children.removeAll(bddChildren);
      node = new Node(bdd, bddChildren);
      _children.add(node);
      nodeStreams.add(Stream.of(node));
      return nodeStreams.stream().flatMap(Function.identity());
    }

    public Stream<AtomicPredicate> atomicPredicates(BDD bdd) {
      assert bdd.imp(this._headerspace).isOne();

      if (bdd.equals(this._headerspace)) {
        return atomicPredicates();
      }

      Stream<AtomicPredicate> result = Stream.empty();
      for (Node child : _children) {
        BDD intersection = child._headerspace.and(bdd);
        if (!intersection.isZero()) {
          result = Streams.concat(result, child.atomicPredicates(intersection));
          if (intersection.equals(bdd)) {
            // nothing left
            break;
          } else {
            bdd = bdd.and(child._notHeaderspace.get());
          }
        }
      }

      if (!bdd.isZero()) {
        // part of the input bdd is disjoint from the children. so it overlaps this
        assert !_headerspaceMinusChildren.get().isZero();
        result = Streams.concat(result, atomicPredicate());
      }
      return result;
    }
  }

  private List<BDD> _atomicPredicates;

  /** Remember the nodes that correspond to a BDD that has been inserted into the trie. */
  private Map<BDD, List<Node>> _bddNodes;

  private Node _root;

  public BDDTrie(Collection<BDD> bdds) {
    _atomicPredicates = new LinkedList<>();
    _bddNodes = new HashMap<>();
    _root = new Node(bdds.iterator().next().getFactory().one());
    for (BDD bdd : bdds) {
      if (!bdd.isZero()) {
        _bddNodes.put(bdd, _root.insert(bdd).collect(ImmutableList.toImmutableList()));
      }
    }
  }

  public List<BDD> atomicPredicates() {
    // force computation of tree of atomic predicates
    _root.computeIds();
    return ImmutableList.copyOf(_atomicPredicates);
  }

  public Stream<AtomicPredicate> atomicPredicates(BDD bdd) {
    if (_bddNodes.containsKey(bdd)) {
      // bdd is one of the input BDDs.
      return _bddNodes.get(bdd).stream().flatMap(Node::atomicPredicates);
    }

    return _root.atomicPredicates(bdd);
  }

  public void checkInvariants() throws BDDTrieException {
    _root.checkInvariants(new ArrayList<>());
  }
}

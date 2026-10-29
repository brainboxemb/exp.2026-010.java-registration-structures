package emb.experiment.ranking;

/**
 * Candidate 3: custom order-statistic AVL multiset.
 *
 * One node stores one distinct elapsed time:
 * - count: occurrences of this exact time;
 * - subtreeCount: occurrences in the complete subtree.
 *
 * rankOf(value) therefore counts all strictly faster occurrences in O(log N).
 *
 * Nodes come from a preallocated pool. The normal ~500-registration case can
 * run without node allocation after construction; the pool grows geometrically
 * when more distinct values are required.
 */
public final class PooledAvlRanking implements RankingIndex {
    private static final class Node {
        long key;
        int count;
        int height;
        int subtreeCount;
        Node left;
        Node right;
        Node nextFree;

        void reset(long newKey) {
            key = newKey;
            count = 1;
            height = 1;
            subtreeCount = 1;
            left = null;
            right = null;
            nextFree = null;
        }
    }

    private static final class NodePool {
        private Node[] nodes;
        private Node free;
        private int growthEvents;

        NodePool(int initialCapacity) {
            if (initialCapacity < 1)
                throw new IllegalArgumentException("initialCapacity must be positive");

            nodes = new Node[initialCapacity];
            addNewNodes(0, initialCapacity);
        }

        Node acquire(long key) {
            if (free == null)
                grow();

            Node node = free;
            free = node.nextFree;
            node.reset(key);
            return node;
        }

        void release(Node node) {
            node.left = null;
            node.right = null;
            node.nextFree = free;
            free = node;
        }

        int capacity() {
            return nodes.length;
        }

        int growthEvents() {
            return growthEvents;
        }

        private void grow() {
            int oldLength = nodes.length;
            int newLength = oldLength * 2;

            Node[] grown = new Node[newLength];
            System.arraycopy(nodes, 0, grown, 0, oldLength);
            nodes = grown;

            addNewNodes(oldLength, newLength);
            growthEvents++;
        }

        private void addNewNodes(int from, int to) {
            for (int i = from; i < to; i++) {
                Node node = new Node();
                nodes[i] = node;
                node.nextFree = free;
                free = node;
            }
        }
    }

    private final NodePool pool;
    private Node root;
    private int size;
    private int distinctSize;
    private boolean removed;

    public PooledAvlRanking(int initialCapacity) {
        pool = new NodePool(initialCapacity);
    }

    @Override
    public void add(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        root = insert(root, elapsedMillis);
    }

    @Override
    public boolean remove(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        removed = false;
        root = removeOne(root, elapsedMillis);
        return removed;
    }

    @Override
    public void prepare() {
        // Always ready.
    }

    @Override
    public int rankOf(long elapsedMillis) {
        Node node = root;
        int faster = 0;

        while (node != null) {
            if (elapsedMillis < node.key) {
                node = node.left;
            } else if (elapsedMillis > node.key) {
                faster += subtreeCount(node.left) + node.count;
                node = node.right;
            } else {
                return faster + subtreeCount(node.left) + 1;
            }
        }

        return 0;
    }

    @Override public int size() { return size; }
    @Override public int distinctSize() { return distinctSize; }
    @Override public int capacity() { return pool.capacity(); }
    @Override public int growthEvents() { return pool.growthEvents(); }

    private Node insert(Node node, long key) {
        if (node == null) {
            size++;
            distinctSize++;
            return pool.acquire(key);
        }

        if (key < node.key) {
            node.left = insert(node.left, key);
        } else if (key > node.key) {
            node.right = insert(node.right, key);
        } else {
            node.count++;
            size++;
            update(node);
            return node;
        }

        update(node);
        return balance(node);
    }

    private Node removeOne(Node node, long key) {
        if (node == null)
            return null;

        if (key < node.key) {
            node.left = removeOne(node.left, key);
        } else if (key > node.key) {
            node.right = removeOne(node.right, key);
        } else {
            removed = true;
            size--;

            if (node.count > 1) {
                node.count--;
                update(node);
                return node;
            }

            distinctSize--;

            if (node.left == null) {
                Node right = node.right;
                pool.release(node);
                return right;
            }

            if (node.right == null) {
                Node left = node.left;
                pool.release(node);
                return left;
            }

            Node successor = minimum(node.right);
            node.key = successor.key;
            node.count = successor.count;
            node.right = deleteWholeNode(node.right, successor.key);
        }

        update(node);
        return balance(node);
    }

    /**
     * Removes a tree node after its key/count have been moved elsewhere.
     * Global occurrence/distinct counters must not change here.
     */
    private Node deleteWholeNode(Node node, long key) {
        if (key < node.key) {
            node.left = deleteWholeNode(node.left, key);
        } else if (key > node.key) {
            node.right = deleteWholeNode(node.right, key);
        } else {
            if (node.left == null) {
                Node right = node.right;
                pool.release(node);
                return right;
            }

            if (node.right == null) {
                Node left = node.left;
                pool.release(node);
                return left;
            }

            Node successor = minimum(node.right);
            node.key = successor.key;
            node.count = successor.count;
            node.right = deleteWholeNode(node.right, successor.key);
        }

        update(node);
        return balance(node);
    }

    private static Node minimum(Node node) {
        Node current = node;
        while (current.left != null)
            current = current.left;
        return current;
    }

    private static Node balance(Node node) {
        int balance = height(node.left) - height(node.right);

        if (balance > 1) {
            if (height(node.left.left) < height(node.left.right))
                node.left = rotateLeft(node.left);
            return rotateRight(node);
        }

        if (balance < -1) {
            if (height(node.right.right) < height(node.right.left))
                node.right = rotateRight(node.right);
            return rotateLeft(node);
        }

        return node;
    }

    private static Node rotateLeft(Node root) {
        Node next = root.right;
        root.right = next.left;
        next.left = root;

        update(root);
        update(next);
        return next;
    }

    private static Node rotateRight(Node root) {
        Node next = root.left;
        root.left = next.right;
        next.right = root;

        update(root);
        update(next);
        return next;
    }

    private static void update(Node node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
        node.subtreeCount = node.count + subtreeCount(node.left) + subtreeCount(node.right);
    }

    private static int height(Node node) {
        return node == null ? 0 : node.height;
    }

    private static int subtreeCount(Node node) {
        return node == null ? 0 : node.subtreeCount;
    }

    private static void requireElapsed(long value) {
        if (value < 0)
            throw new IllegalArgumentException("elapsedMillis must be nonnegative");
    }
}

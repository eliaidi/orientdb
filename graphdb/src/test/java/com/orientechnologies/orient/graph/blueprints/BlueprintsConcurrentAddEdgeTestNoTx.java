package com.orientechnologies.orient.graph.blueprints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import com.orientechnologies.common.collection.OMultiCollectionIterator;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.orient.OrientBaseGraph;
import com.tinkerpop.blueprints.impls.orient.OrientEdgeType;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import com.tinkerpop.blueprints.impls.orient.OrientGraphNoTx;
import com.tinkerpop.blueprints.impls.orient.OrientVertexType;

public class BlueprintsConcurrentAddEdgeTestNoTx {
  protected static final int    VERTEXES_COUNT = 1000;
  protected static final int    EDGES_COUNT    = 5 * VERTEXES_COUNT;
  protected static final int    THREADS        = 16;
  protected static final String URL            = "plocal:./target/databases/blueprintsConcurrentAddEdgeTest";

  protected final ConcurrentSkipListMap<String, TestVertex> vertexesToCreate = new ConcurrentSkipListMap<String, TestVertex>();
  protected final List<TestVertex>                          vertexes         = new ArrayList<TestVertex>();
  protected final List<TestEdge>                            edges            = new ArrayList<TestEdge>();

  protected AtomicInteger  indexCollisionsCreate   = new AtomicInteger();
  protected AtomicInteger  versionCollisionsCreate = new AtomicInteger();
  protected CountDownLatch latchCreate             = new CountDownLatch(1);
  protected final Random   random                  = new Random();

  protected CountDownLatch latchDelete             = new CountDownLatch(1);
  protected AtomicInteger  versionCollisionsDelete = new AtomicInteger();

  protected long  beginTime      = 0;
  private boolean printTempStats = false;

  protected OrientBaseGraph getGraph() {
    OGlobalConfiguration.SQL_GRAPH_CONSISTENCY_MODE.setValue("notx_sync_repair");
    return new OrientGraphNoTx(URL);
  }

  @Test
  public void testCreateEdge() throws Exception {
    OrientBaseGraph graph = getGraph();
    graph.drop();

    generateVertexes();
    generateEdges();
    initGraph();

    System.out.println("Start modifying graph concurrently...");
    modifyGraphConcurrently();
    System.out.println("Checking the graph...");
    assertCreatedGraph();

    System.out.println("Start deleting graph concurrently...");
    deleteGraphConcurrently();
    System.out.println("Checking the graph...");
    assertDeletedGraph();
    System.out.println("Finished.");

    graph = getGraph();
    graph.drop();
  }

  private void generateVertexes() {
    for (int i = 0; i < VERTEXES_COUNT; i++) {
      TestVertex vertex = new TestVertex();
      vertex.uuid = UUID.randomUUID().toString();
      vertex.inEdges = Collections.newSetFromMap(new ConcurrentHashMap<TestEdge, Boolean>());
      vertex.outEdges = Collections.newSetFromMap(new ConcurrentHashMap<TestEdge, Boolean>());

      vertexes.add(vertex);
      vertexesToCreate.put(vertex.uuid, vertex);
    }
  }

  private void generateEdges() {
    for (int i = 0; i < EDGES_COUNT; i++) {
      int fromVertexIndex = random.nextInt(vertexes.size());
      int toVertexIndex = random.nextInt(vertexes.size());

      TestVertex fromVertex = vertexes.get(fromVertexIndex);
      TestVertex toVertex = vertexes.get(toVertexIndex);

      TestEdge edge = new TestEdge();
      edge.uuid = UUID.randomUUID().toString();
      edge.in = fromVertex.uuid;
      edge.out = toVertex.uuid;
      edges.add(edge);

      fromVertex.outEdges.add(edge);
      toVertex.inEdges.add(edge);
    }
  }

  private void initGraph() {
    OrientBaseGraph graph = getGraph();
    graph.setAutoStartTx(false);
    graph.commit();

    final OrientVertexType vertexType = graph.createVertexType("TestVertex");
    vertexType.createProperty("uuid", OType.STRING);
    vertexType.createIndex("TestVertexUuidIndex", OClass.INDEX_TYPE.UNIQUE_HASH_INDEX, "uuid");

    final OrientEdgeType edgeType = graph.createEdgeType("TestEdge");
    edgeType.createProperty("uuid", OType.STRING);
    edgeType.createIndex("TestEdgeUuidIndex", OClass.INDEX_TYPE.UNIQUE_HASH_INDEX, "uuid");
    graph.shutdown();
  }

  private void modifyGraphConcurrently() throws Exception {
    ExecutorService executorService = Executors.newCachedThreadPool();

    beginTime = System.currentTimeMillis();

    List<Future<Void>> futures = new ArrayList<Future<Void>>();
    for (int i = 0; i < THREADS; i++)
      futures.add(executorService.submit(new ConcurrentGraphCreator()));

    latchCreate.countDown();

    for (Future<Void> future : futures)
      future.get();

    final OrientBaseGraph g = getGraph();
    printStats(g);
    g.shutdown();
  }

  private void deleteGraphConcurrently() throws Exception {
    ExecutorService executorService = Executors.newCachedThreadPool();

    beginTime = System.currentTimeMillis();

    List<Future<Void>> futures = new ArrayList<Future<Void>>();
    for (int i = 0; i < THREADS; i++)
      futures.add(executorService.submit(new ConcurrentGraphRemover()));

    latchDelete.countDown();

    for (Future<Void> future : futures)
      future.get();

    final OrientBaseGraph g = getGraph();
    printStats(g);
    g.shutdown();
  }

  private void assertCreatedGraph() {
    OrientBaseGraph graph = getGraph();
    graph.setUseLightweightEdges(false);

    Assert.assertEquals(VERTEXES_COUNT, graph.countVertices("TestVertex"));
    Assert.assertEquals(EDGES_COUNT, graph.countEdges("TestEdge"));

    for (TestVertex vertex : vertexes) {
      Iterable<Vertex> vertexes = graph
          .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + vertex.uuid + "'")).execute();

      Assert.assertTrue(vertexes.iterator().hasNext());
    }

    for (TestEdge edge : edges) {
      Iterable<Edge> edges = graph.command(new OSQLSynchQuery<Edge>("select from TestEdge where uuid = '" + edge.uuid + "'"))
          .execute();

      Assert.assertTrue(edges.iterator().hasNext());
    }

    for (TestVertex vertex : vertexes) {
      Iterable<Vertex> vertexes = graph
          .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + vertex.uuid + "'")).execute();

      Assert.assertTrue(vertexes.iterator().hasNext());

      Vertex gVertex = vertexes.iterator().next();

      Iterable<Edge> outEdges = gVertex.getEdges(Direction.OUT);
      Iterator<Edge> outGEdgesIterator = outEdges.iterator();

      int counter = 0;
      while (outGEdgesIterator.hasNext()) {
        counter++;

        Edge gEdge = outGEdgesIterator.next();
        TestEdge testEdge = new TestEdge();
        testEdge.uuid = gEdge.getProperty("uuid");

        Assert.assertTrue(vertex.outEdges.contains(testEdge));
      }
      Assert.assertEquals(vertex.outEdges.size(), counter);

      Iterable<Edge> inEdges = gVertex.getEdges(Direction.IN);
      Iterator<Edge> inGEdgesIterator = inEdges.iterator();

      counter = 0;
      while (inGEdgesIterator.hasNext()) {
        counter++;

        Edge gEdge = inGEdgesIterator.next();

        TestEdge testEdge = new TestEdge();
        testEdge.uuid = gEdge.getProperty("uuid");

        Assert.assertTrue(vertex.inEdges.contains(testEdge));
      }
      Assert.assertEquals(vertex.inEdges.size(), counter);

    }

    graph.shutdown();
  }

  private void assertDeletedGraph() {
    OrientBaseGraph graph = getGraph();
    graph.setUseLightweightEdges(false);

    Assert.assertEquals(VERTEXES_COUNT, graph.countVertices("TestVertex"));
    Assert.assertEquals(0, graph.countEdges("TestEdge"));

    for (TestVertex vertex : vertexes) {
      Iterable<Vertex> vertexes = graph
          .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + vertex.uuid + "'")).execute();

      Assert.assertTrue(vertexes.iterator().hasNext());

      Vertex gVertex = vertexes.iterator().next();

      OMultiCollectionIterator<Edge> outEdges = (OMultiCollectionIterator<Edge>) gVertex.getEdges(Direction.OUT);

      Assert.assertEquals(outEdges.size(), 0);

      OMultiCollectionIterator<Edge> inEdges = (OMultiCollectionIterator<Edge>) gVertex.getEdges(Direction.IN);

      Assert.assertEquals(inEdges.size(), 0);
    }
    graph.shutdown();
  }

  private class TestVertex {
    private String uuid;

    private Set<TestEdge> outEdges;
    private Set<TestEdge> inEdges;
  }

  private class TestEdge {
    private String uuid;

    private String in;
    private String out;

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;

      TestEdge testEdge = (TestEdge) o;

      if (uuid != null ? !uuid.equals(testEdge.uuid) : testEdge.uuid != null)
        return false;

      return true;
    }

    @Override
    public int hashCode() {
      return uuid != null ? uuid.hashCode() : 0;
    }
  }

  private class ConcurrentGraphCreator implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      OrientBaseGraph graph = getGraph();
      latchCreate.await();
      final int startIndex = random.nextInt(vertexes.size());

      final String startUUID = vertexes.get(startIndex).uuid;
      String currentUUID = startUUID;
      try {
        do {
          TestVertex fromVertex = vertexesToCreate.get(currentUUID);

          for (TestEdge edge : fromVertex.outEdges) {
            TestVertex toVertex = vertexesToCreate.get(edge.out);

            boolean success = false;
            while (!success)
              try {
                Iterable<Vertex> vertexes = graph
                    .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + fromVertex.uuid + "'")).execute();

                Vertex gFromVertex;

                Iterator<Vertex> gVertexesIterator = vertexes.iterator();

                if (!gVertexesIterator.hasNext()) {
                  graph.command(new OCommandSQL("CREATE VERTEX TestVertex SET uuid = '" + fromVertex.uuid + "'")).execute();

                  vertexes = graph
                      .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + fromVertex.uuid + "'"))
                      .execute();
                  gVertexesIterator = vertexes.iterator();
                  gFromVertex = gVertexesIterator.next();
                } else {
                  gFromVertex = gVertexesIterator.next();
                }

                vertexes = graph.command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + toVertex.uuid + "'"))
                    .execute();

                Vertex gToVertex;

                gVertexesIterator = vertexes.iterator();
                if (!gVertexesIterator.hasNext()) {
                  graph.command(new OCommandSQL("CREATE VERTEX TestVertex SET uuid = '" + toVertex.uuid + "'")).execute();
                  vertexes = graph
                      .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + toVertex.uuid + "'")).execute();

                  gVertexesIterator = vertexes.iterator();
                  gToVertex = gVertexesIterator.next();
                } else {
                  gToVertex = gVertexesIterator.next();
                }

                Iterable<Edge> edges = graph
                    .command(new OSQLSynchQuery<Edge>("select from TestEdge where uuid = '" + edge.uuid + "'")).execute();

                if (!edges.iterator().hasNext()) {
                  graph.command(new OCommandSQL("CREATE EDGE TestEdge FROM " + gFromVertex.getId() + " TO " + gToVertex.getId()
                      + " SET uuid = '" + edge.uuid + "'")).execute();
                }

                graph.commit();

                success = true;

              } catch (OConcurrentModificationException e) {
                graph.rollback();

                versionCollisionsCreate.incrementAndGet();

                if (printTempStats && versionCollisionsCreate.get() % 1000 == 0)
                  printStats(graph);

                Thread.yield();
              } catch (ORecordDuplicatedException e) {
                graph.rollback();

                indexCollisionsCreate.incrementAndGet();

                if (indexCollisionsCreate.get() % 1000 == 0)
                  printStats(graph);

                Thread.yield();
              }
          }

          if (fromVertex.outEdges.isEmpty()) {
            boolean success = false;
            while (!success)
              try {
                Iterable<Vertex> vertexes = graph
                    .command(new OSQLSynchQuery<Vertex>("select from TestVertex where uuid = '" + fromVertex.uuid + "'")).execute();

                Iterator<Vertex> gVertexesIterator = vertexes.iterator();

                if (!gVertexesIterator.hasNext()) {
                  graph.command(new OCommandSQL("CREATE VERTEX TestVertex SET uuid = '" + fromVertex.uuid + "'")).execute();
                }

                success = true;
              } catch (OConcurrentModificationException e) {
                graph.rollback();

                versionCollisionsCreate.incrementAndGet();
                Thread.yield();
              } catch (ORecordDuplicatedException e) {
                graph.rollback();

                indexCollisionsCreate.incrementAndGet();
                Thread.yield();
              }
          }

          currentUUID = vertexesToCreate.higherKey(currentUUID);
          if (currentUUID == null)
            currentUUID = vertexesToCreate.firstKey();
        } while (!startUUID.equals(currentUUID));
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      } finally {
        graph.shutdown();
      }

      return null;

    }
  }

  protected void printStats(OrientBaseGraph g) {
    System.out.println("Graph " + (getGraph() instanceof OrientGraph ? "TX" : "NOTX") + " stats threads: " + THREADS
        + " create duplication exceptions: " + indexCollisionsCreate.get() + " create version exceptions: "
        + versionCollisionsCreate.get() + " delete version exceptions: " + versionCollisionsDelete.get() + " vertexes: "
        + g.countVertices() + " edges: " + g.countEdges() + " TIME: "
        + (beginTime > 0 ? System.currentTimeMillis() - beginTime : "?"));
  }

  private class ConcurrentGraphRemover implements Callable<Void> {
    @Override
    public Void call() throws Exception {
      OrientBaseGraph graph = getGraph();
      latchDelete.await();

      for (TestEdge edge : edges) {
        do {
          try {
            graph.command(new OCommandSQL("delete edge TestEdge where uuid = '" + edge.uuid + "'")).execute();
            graph.commit();
            break;

          } catch (ORecordNotFoundException e) {
            // OK
            break;
          } catch (OConcurrentModificationException e) {
            graph.rollback();

            versionCollisionsDelete.incrementAndGet();

            if (printTempStats && versionCollisionsDelete.get() % 1000 == 0)
              printStats(graph);

            Thread.yield();
          }
        } while (true);
      }
      return null;
    }
  }
}

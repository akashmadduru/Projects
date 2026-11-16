package com.acme.ecommerce.product.search;

/**
 * The single-statement hybrid ranking query.
 *
 * <p>Shape: filters are applied INSIDE each leg (so a leg's top-N is already restricted), each leg
 * keeps its best {@code :topN}, and Reciprocal Rank Fusion ({@code sum(1 / (k + rank))}) merges
 * them. Keyword leg: {@code websearch_to_tsquery} OR the trigram {@code %} operator, which - unlike
 * the legacy {@code similarity(..) > x} - can use the GIN trigram index. Vector leg: HNSW cosine
 * distance, junk neighbours beyond {@code :maxDist} dropped AFTER the index scan so they cannot
 * defeat it.
 *
 * <p>pg_trgm lives in schema {@code product}; pgvector lives in {@code public}. BOTH are fully
 * qualified because neither schema is guaranteed to be on the {@code search_path} of every
 * connection.
 *
 * <p>The result is at most {@code 2 * topN} rows - the explicit ceiling on a search's total size.
 */
final class HybridSearchSql {

  private HybridSearchSql() {}

  /**
   * @param keywordLeg include the full-text/trigram leg (binds {@code :q}, {@code :lq})
   * @param vectorLeg include the HNSW leg (binds {@code :qvec}, {@code :model}, {@code :maxDist})
   * @param filters the shared predicate from {@link ProductFilterSql}
   */
  static String build(boolean keywordLeg, boolean vectorLeg, String filters, SearchSort sort) {
    StringBuilder sql = new StringBuilder("with ");
    StringBuilder legs = new StringBuilder();
    if (keywordLeg) {
      sql.append(
          "kw as ("
              + " select p.id, row_number() over (order by"
              + "   ts_rank_cd(p.search_vector, websearch_to_tsquery('english', :q))"
              + "   + product.similarity(lower(p.name), :lq) desc, p.id) as rnk"
              + " from product.products p"
              + " where "
              + filters
              + "   and (p.search_vector @@ websearch_to_tsquery('english', :q)"
              + "        or lower(p.name) operator(product.%) :lq)"
              + " order by rnk limit :topN), ");
      legs.append("select id, rnk, cast('kw' as text) as src from kw");
    }
    if (vectorLeg) {
      sql.append(
          "vec as ("
              + " select v.id, row_number() over (order by v.dist, v.id) as rnk from ("
              + "   select e.product_id as id,"
              + "          (e.embedding operator(public.<=>) cast(:qvec as public.vector)) as dist"
              + "   from product.product_embeddings e"
              + "   join product.products p on p.id = e.product_id"
              + "   where e.model = :model and "
              + filters
              + "   order by e.embedding operator(public.<=>) cast(:qvec as public.vector)"
              + "   limit :topN) v"
              + " where v.dist <= :maxDist), ");
      if (legs.length() > 0) {
        legs.append(" union all ");
      }
      legs.append("select id, rnk, cast('vec' as text) as src from vec");
    }
    sql.append(
            "fused as ("
                + " select u.id,"
                + "   cast(sum(1.0 / (cast(:k as int) + u.rnk)) as double precision) as score,"
                + "   bool_or(u.src = 'kw') as in_kw, bool_or(u.src = 'vec') as in_vec"
                + " from (")
        .append(legs)
        .append(") u group by u.id) ")
        .append(
            "select f.id, f.score, f.in_kw, f.in_vec"
                + " from fused f join product.products p on p.id = f.id"
                + " order by ")
        .append(sort.orderBy());
    return sql.toString();
  }
}

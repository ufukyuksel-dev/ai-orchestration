`ArticleList` passes the raw `limit` query string to `FindManyArticle`, which runs `strconv.Atoi(limit)` and falls
back to `limit_int = 20` on any error (missing or non-numeric).

ANSWER:
defaultLimit: 20
invalidLimit: 20
function: articles.FindManyArticle

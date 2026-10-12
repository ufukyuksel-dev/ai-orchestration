// Hidden benchmark test for task gn02. Black-box: it starts the real application (main) on a free port with a
// throw-away SQLite database (DB_PATH), creates its data through the public API and checks only the HTTP contract
// stated in the task.
package main

import (
	"bytes"
	"encoding/json"
	"io"
	"mime"
	"net"
	"net/http"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

var _, _ = sort.Strings, strings.Join

type benchGN02App struct {
	t    *testing.T
	base string
}

type benchGN02Resp struct {
	Status int
	Body   []byte
}

func benchGN02Start(t *testing.T) *benchGN02App {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("no free port: %v", err)
	}
	port := strconv.Itoa(ln.Addr().(*net.TCPAddr).Port)
	_ = ln.Close()
	t.Setenv("DB_PATH", filepath.Join(t.TempDir(), "bench.db"))
	t.Setenv("PORT", port)
	gin.SetMode(gin.ReleaseMode)
	gin.DefaultWriter = io.Discard
	go main()
	deadline := time.Now().Add(30 * time.Second)
	for {
		conn, err := net.DialTimeout("tcp", "127.0.0.1:"+port, 200*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the application did not start listening on port %s", port)
		}
		time.Sleep(50 * time.Millisecond)
	}
	return &benchGN02App{t: t, base: "http://127.0.0.1:" + port}
}

func (a *benchGN02App) do(method, path, token string, payload interface{}) benchGN02Resp {
	a.t.Helper()
	var body io.Reader
	if payload != nil {
		raw, err := json.Marshal(payload)
		if err != nil {
			a.t.Fatal(err)
		}
		body = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, a.base+path, body)
	if err != nil {
		a.t.Fatal(err)
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Token "+token)
	}
	client := &http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	resp, err := client.Do(req)
	if err != nil {
		a.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		a.t.Fatal(err)
	}
	return benchGN02Resp{Status: resp.StatusCode, Body: data}
}

func (a *benchGN02App) must(status int, method, path, token string, payload interface{}) benchGN02Resp {
	a.t.Helper()
	r := a.do(method, path, token, payload)
	if r.Status != status {
		a.t.Fatalf("%s %s: status %d, want %d; body: %s", method, path, r.Status, status, r.Body)
	}
	return r
}

func (a *benchGN02App) register(username string) string {
	a.t.Helper()
	r := a.must(http.StatusCreated, "POST", "/api/users", "", map[string]interface{}{"user": map[string]string{
		"username": username, "email": username + "@bench.example", "password": "benchpassword1"}})
	return benchGN02Token(a.t, r.Body, "register "+username)
}

func benchGN02Token(t *testing.T, body []byte, what string) string {
	t.Helper()
	var out struct {
		User struct {
			Token string `json:"token"`
		} `json:"user"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.User.Token == "" {
		t.Fatalf("%s: no token in %s (%v)", what, body, err)
	}
	return out.User.Token
}

func (a *benchGN02App) article(token, title string, tags ...string) string {
	a.t.Helper()
	if tags == nil {
		tags = []string{}
	}
	r := a.must(http.StatusCreated, "POST", "/api/articles", token, map[string]interface{}{"article": map[string]interface{}{
		"title": title, "description": "about " + title, "body": "body of " + title, "tagList": tags}})
	var out struct {
		Article struct {
			Slug string `json:"slug"`
		} `json:"article"`
	}
	if err := json.Unmarshal(r.Body, &out); err != nil || out.Article.Slug == "" {
		a.t.Fatalf("create article %q: %v %s", title, err, r.Body)
	}
	return out.Article.Slug
}

func (a *benchGN02App) comment(token, slug, body string) int {
	a.t.Helper()
	r := a.must(http.StatusCreated, "POST", "/api/articles/"+slug+"/comments", token,
		map[string]interface{}{"comment": map[string]string{"body": body}})
	var out struct {
		Comment struct {
			ID int `json:"id"`
		} `json:"comment"`
	}
	if err := json.Unmarshal(r.Body, &out); err != nil || out.Comment.ID == 0 {
		a.t.Fatalf("create comment: %v %s", err, r.Body)
	}
	return out.Comment.ID
}

func (a *benchGN02App) follow(token, username string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/profiles/"+username+"/follow", token, nil)
}

func (a *benchGN02App) favorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/articles/"+slug+"/favorite", token, nil)
}

func (a *benchGN02App) unfavorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug+"/favorite", token, nil)
}

// getJSON: status 200, an application/json content type, and the body decoded into out.
func (a *benchGN02App) getJSON(path, token string, out interface{}) {
	a.t.Helper()
	req, err := http.NewRequest("GET", a.base+path, nil)
	if err != nil {
		a.t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Token "+token)
	}
	resp, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		a.t.Fatalf("GET %s: %v", path, err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		a.t.Fatalf("GET %s: status %d, want 200; body: %s", path, resp.StatusCode, data)
	}
	if mt, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		a.t.Fatalf("GET %s: content type %q, want application/json", path, resp.Header.Get("Content-Type"))
	}
	if err := json.Unmarshal(data, out); err != nil {
		a.t.Fatalf("GET %s: %v: %s", path, err, data)
	}
}

type benchGN02Article struct {
	Slug           string `json:"slug"`
	Favorited      bool   `json:"favorited"`
	FavoritesCount int    `json:"favoritesCount"`
	Author         struct {
		Username string `json:"username"`
	} `json:"author"`
}

type benchGN02List struct {
	Articles      []benchGN02Article `json:"articles"`
	ArticlesCount int                `json:"articlesCount"`
}

type benchGN02Comments struct {
	Comments []struct {
		ID     int    `json:"id"`
		Body   string `json:"body"`
		Author struct {
			Username string `json:"username"`
		} `json:"author"`
	} `json:"comments"`
}

func (a *benchGN02App) list(path, token string) benchGN02List {
	a.t.Helper()
	var out benchGN02List
	a.getJSON(path, token, &out)
	return out
}

func (a *benchGN02App) single(slug, token string) benchGN02Article {
	a.t.Helper()
	var out struct {
		Article benchGN02Article `json:"article"`
	}
	a.getJSON("/api/articles/"+slug, token, &out)
	return out.Article
}

func (a *benchGN02App) comments(slug string) benchGN02Comments {
	a.t.Helper()
	var out benchGN02Comments
	a.getJSON("/api/articles/"+slug+"/comments", "", &out)
	return out
}

func (a *benchGN02App) slugs(path, token string) []string {
	a.t.Helper()
	out := []string{}
	for _, art := range a.list(path, token).Articles {
		out = append(out, art.Slug)
	}
	sort.Strings(out)
	return out
}

func benchGN02Same(t *testing.T, got, want []string, what string) {
	t.Helper()
	w := append([]string{}, want...)
	sort.Strings(w)
	if strings.Join(got, ",") != strings.Join(w, ",") {
		t.Fatalf("%s: got %q, want %q", what, got, w)
	}
}

func (a *benchGN02App) commentBodies(slug string) []string {
	a.t.Helper()
	out := []string{}
	for _, c := range a.comments(slug).Comments {
		out = append(out, c.Author.Username+":"+c.Body)
	}
	sort.Strings(out)
	return out
}

func (a *benchGN02App) deleteAccount(token string) {
	a.t.Helper()
	r := a.do("DELETE", "/api/user", token, nil)
	if r.Status != http.StatusOK && r.Status != http.StatusNoContent {
		a.t.Fatalf("DELETE /api/user: status %d, want 200 or 204; body: %s", r.Status, r.Body)
	}
}

func (a *benchGN02App) following(viewerToken, username string) bool {
	a.t.Helper()
	var out struct {
		Profile struct {
			Following bool `json:"following"`
		} `json:"profile"`
	}
	a.getJSON("/api/profiles/"+username, viewerToken, &out)
	return out.Profile.Following
}

func TestBenchGN02DeleteAccount(t *testing.T) {
	app := benchGN02Start(t)

	alice := app.register("alice")
	bobby := app.register("bobby")
	carol := app.register("carol")
	david := app.register("david")
	erinx := app.register("erinx")
	frank := app.register("frank")

	// Authors appear in an order unlike the registration order, so the internal author rows are numbered
	// differently from the users (bobby, the account deleted below, is the last author).
	dd := app.article(david, "David Dispatch", "news")
	aa := app.article(alice, "Alice Almanac", "news")
	ee := app.article(erinx, "Erinx Essays")
	cc := app.article(carol, "Carol Chronicle")
	bb1 := app.article(bobby, "Bobby Bulletin", "news")
	bb2 := app.article(bobby, "Bobby Backlog")

	app.comment(bobby, aa, "bobby was here")
	app.comment(alice, aa, "alice replies")
	app.comment(bobby, dd, "bobby again")
	app.comment(carol, bb1, "carol on bobby")
	app.comment(david, cc, "david says")

	app.favorite(bobby, aa)
	app.favorite(bobby, dd)
	app.favorite(bobby, cc)
	app.favorite(alice, dd)
	app.favorite(alice, bb1)
	app.favorite(carol, aa)
	app.favorite(erinx, ee)

	app.follow(alice, "bobby")
	app.follow(david, "bobby")
	app.follow(david, "alice")
	app.follow(bobby, "carol")
	app.follow(bobby, "alice")
	app.follow(frank, "alice")

	// sanity before the deletion
	benchGN02Same(t, app.slugs("/api/articles/feed", alice), []string{bb1, bb2}, "alice's feed before")
	if got := app.single(aa, "").FavoritesCount; got != 2 {
		t.Fatalf("favoritesCount of %s before: %d, want 2", aa, got)
	}

	// anonymous: rejected, nothing deleted
	app.must(http.StatusUnauthorized, "DELETE", "/api/user", "", nil)
	app.must(http.StatusOK, "GET", "/api/profiles/bobby", "", nil)
	app.must(http.StatusOK, "GET", "/api/articles/"+bb1, "", nil)

	app.deleteAccount(bobby)

	app.must(http.StatusNotFound, "GET", "/api/profiles/bobby", "", nil)
	if r := app.do("POST", "/api/users/login", "", map[string]interface{}{"user": map[string]string{
		"email": "bobby@bench.example", "password": "benchpassword1"}}); r.Status != http.StatusUnauthorized {
		t.Fatalf("login of the deleted account: status %d, want 401", r.Status)
	}
	app.must(http.StatusUnauthorized, "GET", "/api/user", bobby, nil)
	app.must(http.StatusNotFound, "GET", "/api/articles/"+bb1, "", nil)
	app.must(http.StatusNotFound, "GET", "/api/articles/"+bb2, "", nil)

	rest := []string{dd, aa, ee, cc}
	for _, token := range []string{"", alice} {
		benchGN02Same(t, app.slugs("/api/articles?limit=50", token), rest, "GET /api/articles after the deletion")
	}
	benchGN02Same(t, app.slugs("/api/articles?tag=news", ""), []string{dd, aa}, "GET /api/articles?tag=news after the deletion")
	benchGN02Same(t, app.slugs("/api/articles?author=bobby", ""), []string{}, "GET /api/articles?author=bobby after the deletion")
	benchGN02Same(t, app.slugs("/api/articles?favorited=bobby", ""), []string{}, "GET /api/articles?favorited=bobby after the deletion")
	benchGN02Same(t, app.slugs("/api/articles?author=alice", ""), []string{aa}, "GET /api/articles?author=alice after the deletion")
	benchGN02Same(t, app.slugs("/api/articles?author=david", ""), []string{dd}, "GET /api/articles?author=david after the deletion")
	benchGN02Same(t, app.slugs("/api/articles?favorited=alice", ""), []string{dd}, "GET /api/articles?favorited=alice after the deletion")
	benchGN02Same(t, app.slugs("/api/articles/feed", alice), []string{}, "alice's feed after the deletion")
	benchGN02Same(t, app.slugs("/api/articles/feed", david), []string{aa}, "david's feed after the deletion")

	benchGN02Same(t, app.commentBodies(aa), []string{"alice:alice replies"}, "comments of "+aa)
	benchGN02Same(t, app.commentBodies(dd), []string{}, "comments of "+dd)
	benchGN02Same(t, app.commentBodies(cc), []string{"david:david says"}, "comments of "+cc)

	counts := map[string]int{dd: 1, aa: 1, ee: 1, cc: 0}
	for _, slug := range rest {
		if got := app.single(slug, "").FavoritesCount; got != counts[slug] {
			t.Fatalf("favoritesCount of %s after the deletion: %d, want %d", slug, got, counts[slug])
		}
	}
	for _, art := range app.list("/api/articles?limit=50", "").Articles {
		if art.FavoritesCount != counts[art.Slug] {
			t.Fatalf("GET /api/articles: favoritesCount of %s after the deletion: %d, want %d", art.Slug, art.FavoritesCount, counts[art.Slug])
		}
	}
	if !app.single(dd, alice).Favorited || !app.single(aa, carol).Favorited || app.single(cc, alice).Favorited {
		t.Fatalf("favorites of the remaining users changed")
	}
	if !app.following(david, "alice") || !app.following(frank, "alice") || app.following(alice, "carol") {
		t.Fatalf("follows of the remaining users changed")
	}
	app.must(http.StatusOK, "GET", "/api/user", alice, nil)
	app.must(http.StatusOK, "GET", "/api/profiles/carol", "", nil)

	// the username and email are free again; the new account starts empty
	nb := app.register("bobby")
	app.must(http.StatusUnauthorized, "GET", "/api/user", bobby, nil)
	app.must(http.StatusOK, "GET", "/api/user", nb, nil)
	benchGN02Same(t, app.slugs("/api/articles?author=bobby", nb), []string{}, "articles of the new bobby")
	benchGN02Same(t, app.slugs("/api/articles?favorited=bobby", nb), []string{}, "favorites of the new bobby")
	benchGN02Same(t, app.slugs("/api/articles/feed", nb), []string{}, "feed of the new bobby")
	for _, slug := range rest {
		if app.single(slug, nb).Favorited {
			t.Fatalf("the new bobby sees %s as favorited", slug)
		}
	}
	if app.following(alice, "bobby") || app.following(david, "bobby") {
		t.Fatalf("old followers of the deleted account follow the new bobby")
	}

	// an account without any article data can be deleted too
	app.deleteAccount(frank)
	app.must(http.StatusNotFound, "GET", "/api/profiles/frank", "", nil)
	benchGN02Same(t, app.slugs("/api/articles?limit=50", ""), rest, "GET /api/articles after deleting frank")
	app.must(http.StatusUnauthorized, "GET", "/api/articles/feed", "", nil)
}

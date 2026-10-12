// Hidden benchmark test for task gn01. Black-box: it starts the real application (main) on a free port with a
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

type benchGN01App struct {
	t    *testing.T
	base string
}

type benchGN01Resp struct {
	Status int
	Body   []byte
}

func benchGN01Start(t *testing.T) *benchGN01App {
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
	return &benchGN01App{t: t, base: "http://127.0.0.1:" + port}
}

func (a *benchGN01App) do(method, path, token string, payload interface{}) benchGN01Resp {
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
	return benchGN01Resp{Status: resp.StatusCode, Body: data}
}

func (a *benchGN01App) must(status int, method, path, token string, payload interface{}) benchGN01Resp {
	a.t.Helper()
	r := a.do(method, path, token, payload)
	if r.Status != status {
		a.t.Fatalf("%s %s: status %d, want %d; body: %s", method, path, r.Status, status, r.Body)
	}
	return r
}

func (a *benchGN01App) register(username string) string {
	a.t.Helper()
	r := a.must(http.StatusCreated, "POST", "/api/users", "", map[string]interface{}{"user": map[string]string{
		"username": username, "email": username + "@bench.example", "password": "benchpassword1"}})
	return benchGN01Token(a.t, r.Body, "register "+username)
}

func benchGN01Token(t *testing.T, body []byte, what string) string {
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

func (a *benchGN01App) article(token, title string, tags ...string) string {
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

func (a *benchGN01App) comment(token, slug, body string) int {
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

func (a *benchGN01App) follow(token, username string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/profiles/"+username+"/follow", token, nil)
}

func (a *benchGN01App) favorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/articles/"+slug+"/favorite", token, nil)
}

func (a *benchGN01App) unfavorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug+"/favorite", token, nil)
}

// getJSON: status 200, an application/json content type, and the body decoded into out.
func (a *benchGN01App) getJSON(path, token string, out interface{}) {
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

type benchGN01Article struct {
	Slug           string `json:"slug"`
	Favorited      bool   `json:"favorited"`
	FavoritesCount int    `json:"favoritesCount"`
	Author         struct {
		Username string `json:"username"`
	} `json:"author"`
}

type benchGN01List struct {
	Articles      []benchGN01Article `json:"articles"`
	ArticlesCount int                `json:"articlesCount"`
}

type benchGN01Comments struct {
	Comments []struct {
		ID     int    `json:"id"`
		Body   string `json:"body"`
		Author struct {
			Username string `json:"username"`
		} `json:"author"`
	} `json:"comments"`
}

func (a *benchGN01App) list(path, token string) benchGN01List {
	a.t.Helper()
	var out benchGN01List
	a.getJSON(path, token, &out)
	return out
}

func (a *benchGN01App) single(slug, token string) benchGN01Article {
	a.t.Helper()
	var out struct {
		Article benchGN01Article `json:"article"`
	}
	a.getJSON("/api/articles/"+slug, token, &out)
	return out.Article
}

func (a *benchGN01App) comments(slug string) benchGN01Comments {
	a.t.Helper()
	var out benchGN01Comments
	a.getJSON("/api/articles/"+slug+"/comments", "", &out)
	return out
}

func TestBenchGN01FavoritedInLists(t *testing.T) {
	app := benchGN01Start(t)

	alice := app.register("alice")
	bobby := app.register("bobby")
	carol := app.register("carol")
	david := app.register("david")

	// Written in reverse order, so the internal author rows are not numbered like the users.
	dd := app.article(david, "David Daily", "news")
	cc := app.article(carol, "Carol Corner", "news")
	bb := app.article(bobby, "Bobby Blog")
	aa := app.article(alice, "Alice Answers", "news")

	app.favorite(alice, cc)
	app.favorite(alice, bb)
	app.unfavorite(alice, bb)
	app.favorite(david, bb)
	app.favorite(david, aa)
	app.favorite(carol, dd)

	app.follow(alice, "bobby")
	app.follow(alice, "carol")
	app.follow(alice, "david")
	app.follow(bobby, "carol")
	app.follow(bobby, "david")

	all := []string{dd, cc, bb, aa}
	want := map[string]map[string]bool{
		"":    {},
		alice: {cc: true},
		bobby: {},
		carol: {dd: true},
		david: {bb: true, aa: true},
	}
	names := map[string]string{"": "anonymous", alice: "alice", bobby: "bobby", carol: "carol", david: "david"}
	counts := map[string]int{dd: 1, cc: 1, bb: 1, aa: 1}

	for _, token := range []string{"", alice, bobby, carol, david} {
		who := names[token]
		for _, slug := range all {
			if token == "" {
				continue // anonymous callers are only checked in the lists
			}
			got := app.single(slug, token)
			if got.Favorited != want[token][slug] {
				t.Fatalf("GET /api/articles/%s as %s: favorited %v, want %v", slug, who, got.Favorited, want[token][slug])
			}
		}
		for _, path := range []string{"/api/articles?limit=50", "/api/articles?tag=news", "/api/articles?author=bobby"} {
			lst := app.list(path, token)
			if len(lst.Articles) == 0 {
				t.Fatalf("GET %s as %s: no articles", path, who)
			}
			for _, art := range lst.Articles {
				if art.Favorited != want[token][art.Slug] {
					t.Fatalf("GET %s as %s: article %s favorited %v, want %v (as GET /api/articles/%s says)",
						path, who, art.Slug, art.Favorited, want[token][art.Slug], art.Slug)
				}
				if art.FavoritesCount != counts[art.Slug] {
					t.Fatalf("GET %s as %s: article %s favoritesCount %d, want %d", path, who, art.Slug, art.FavoritesCount, counts[art.Slug])
				}
			}
		}
	}

	// the feed of a signed-in user uses the same flags
	for _, token := range []string{alice, bobby} {
		feed := app.list("/api/articles/feed", token)
		if len(feed.Articles) == 0 {
			t.Fatalf("GET /api/articles/feed as %s: empty feed", names[token])
		}
		for _, art := range feed.Articles {
			if art.Favorited != want[token][art.Slug] {
				t.Fatalf("GET /api/articles/feed as %s: article %s favorited %v, want %v", names[token], art.Slug, art.Favorited, want[token][art.Slug])
			}
		}
	}

	// favoriting from the list's point of view follows the single-article endpoint
	app.favorite(bobby, aa)
	for _, art := range app.list("/api/articles?limit=50", bobby).Articles {
		if art.Favorited != (art.Slug == aa) {
			t.Fatalf("GET /api/articles as bobby after favoriting %s: article %s favorited %v", aa, art.Slug, art.Favorited)
		}
	}
	app.must(http.StatusUnauthorized, "POST", "/api/articles/"+aa+"/favorite", "", nil)
}

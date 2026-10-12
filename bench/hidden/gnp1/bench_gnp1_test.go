// Hidden benchmark test for task gnp1. Black-box: it starts the real application (main) on a free port with a
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

type benchGNP1App struct {
	t    *testing.T
	base string
}

type benchGNP1Resp struct {
	Status int
	Body   []byte
}

func benchGNP1Start(t *testing.T) *benchGNP1App {
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
	return &benchGNP1App{t: t, base: "http://127.0.0.1:" + port}
}

func (a *benchGNP1App) do(method, path, token string, payload interface{}) benchGNP1Resp {
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
	return benchGNP1Resp{Status: resp.StatusCode, Body: data}
}

func (a *benchGNP1App) must(status int, method, path, token string, payload interface{}) benchGNP1Resp {
	a.t.Helper()
	r := a.do(method, path, token, payload)
	if r.Status != status {
		a.t.Fatalf("%s %s: status %d, want %d; body: %s", method, path, r.Status, status, r.Body)
	}
	return r
}

func (a *benchGNP1App) register(username string) string {
	a.t.Helper()
	r := a.must(http.StatusCreated, "POST", "/api/users", "", map[string]interface{}{"user": map[string]string{
		"username": username, "email": username + "@bench.example", "password": "benchpassword1"}})
	return benchGNP1Token(a.t, r.Body, "register "+username)
}

func benchGNP1Token(t *testing.T, body []byte, what string) string {
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

func (a *benchGNP1App) article(token, title string, tags ...string) string {
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

func (a *benchGNP1App) comment(token, slug, body string) int {
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

func (a *benchGNP1App) follow(token, username string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/profiles/"+username+"/follow", token, nil)
}

func (a *benchGNP1App) favorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/articles/"+slug+"/favorite", token, nil)
}

func (a *benchGNP1App) unfavorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug+"/favorite", token, nil)
}

// getJSON: status 200, an application/json content type, and the body decoded into out.
func (a *benchGNP1App) getJSON(path, token string, out interface{}) {
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

type benchGNP1Article struct {
	Slug           string `json:"slug"`
	Favorited      bool   `json:"favorited"`
	FavoritesCount int    `json:"favoritesCount"`
	Author         struct {
		Username string `json:"username"`
	} `json:"author"`
}

type benchGNP1List struct {
	Articles      []benchGNP1Article `json:"articles"`
	ArticlesCount int                `json:"articlesCount"`
}

type benchGNP1Comments struct {
	Comments []struct {
		ID     int    `json:"id"`
		Body   string `json:"body"`
		Author struct {
			Username string `json:"username"`
		} `json:"author"`
	} `json:"comments"`
}

func (a *benchGNP1App) list(path, token string) benchGNP1List {
	a.t.Helper()
	var out benchGNP1List
	a.getJSON(path, token, &out)
	return out
}

func (a *benchGNP1App) single(slug, token string) benchGNP1Article {
	a.t.Helper()
	var out struct {
		Article benchGNP1Article `json:"article"`
	}
	a.getJSON("/api/articles/"+slug, token, &out)
	return out.Article
}

func (a *benchGNP1App) comments(slug string) benchGNP1Comments {
	a.t.Helper()
	var out benchGNP1Comments
	a.getJSON("/api/articles/"+slug+"/comments", "", &out)
	return out
}

func TestBenchGNP1DeletedCommentsHidden(t *testing.T) {
	app := benchGNP1Start(t)

	alice := app.register("alice")
	bobby := app.register("bobby")
	a1 := app.article(alice, "Alice Writes")
	a2 := app.article(bobby, "Bobby Writes")

	keep1 := app.comment(bobby, a1, "first")
	gone1 := app.comment(alice, a1, "second")
	keep2 := app.comment(alice, a1, "third")
	gone2 := app.comment(bobby, a2, "only one")

	app.must(http.StatusOK, "DELETE", "/api/articles/"+a1+"/comments/"+strconv.Itoa(gone1), alice, nil)
	app.must(http.StatusOK, "DELETE", "/api/articles/"+a2+"/comments/"+strconv.Itoa(gone2), bobby, nil)

	got := []int{}
	for _, c := range app.comments(a1).Comments {
		got = append(got, c.ID)
	}
	if len(got) != 2 || got[0] != keep1 || got[1] != keep2 {
		t.Fatalf("GET /api/articles/%s/comments after deleting comment %d: ids %v, want [%d %d]", a1, gone1, got, keep1, keep2)
	}
	if n := len(app.comments(a2).Comments); n != 0 {
		t.Fatalf("GET /api/articles/%s/comments after deleting its only comment: %d comments, want 0", a2, n)
	}
	// a comment of somebody else cannot be deleted
	app.must(http.StatusForbidden, "DELETE", "/api/articles/"+a1+"/comments/"+strconv.Itoa(keep1), alice, nil)
	if n := len(app.comments(a1).Comments); n != 2 {
		t.Fatalf("comments after a forbidden delete: %d, want 2", n)
	}
}

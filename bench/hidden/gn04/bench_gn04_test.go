// Hidden benchmark test for task gn04. Black-box: it starts the real application (main) on a free port with a
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

type benchGN04App struct {
	t    *testing.T
	base string
}

type benchGN04Resp struct {
	Status int
	Body   []byte
}

func benchGN04Start(t *testing.T) *benchGN04App {
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
	return &benchGN04App{t: t, base: "http://127.0.0.1:" + port}
}

func (a *benchGN04App) do(method, path, token string, payload interface{}) benchGN04Resp {
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
	return benchGN04Resp{Status: resp.StatusCode, Body: data}
}

func (a *benchGN04App) must(status int, method, path, token string, payload interface{}) benchGN04Resp {
	a.t.Helper()
	r := a.do(method, path, token, payload)
	if r.Status != status {
		a.t.Fatalf("%s %s: status %d, want %d; body: %s", method, path, r.Status, status, r.Body)
	}
	return r
}

func (a *benchGN04App) register(username string) string {
	a.t.Helper()
	r := a.must(http.StatusCreated, "POST", "/api/users", "", map[string]interface{}{"user": map[string]string{
		"username": username, "email": username + "@bench.example", "password": "benchpassword1"}})
	return benchGN04Token(a.t, r.Body, "register "+username)
}

func benchGN04Token(t *testing.T, body []byte, what string) string {
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

func (a *benchGN04App) article(token, title string, tags ...string) string {
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

func (a *benchGN04App) comment(token, slug, body string) int {
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

func (a *benchGN04App) follow(token, username string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/profiles/"+username+"/follow", token, nil)
}

func (a *benchGN04App) favorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/articles/"+slug+"/favorite", token, nil)
}

func (a *benchGN04App) unfavorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug+"/favorite", token, nil)
}

// getJSON: status 200, an application/json content type, and the body decoded into out.
func (a *benchGN04App) getJSON(path, token string, out interface{}) {
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

type benchGN04Article struct {
	Slug           string `json:"slug"`
	Favorited      bool   `json:"favorited"`
	FavoritesCount int    `json:"favoritesCount"`
	Author         struct {
		Username string `json:"username"`
	} `json:"author"`
}

type benchGN04List struct {
	Articles      []benchGN04Article `json:"articles"`
	ArticlesCount int                `json:"articlesCount"`
}

type benchGN04Comments struct {
	Comments []struct {
		ID     int    `json:"id"`
		Body   string `json:"body"`
		Author struct {
			Username string `json:"username"`
		} `json:"author"`
	} `json:"comments"`
}

func (a *benchGN04App) list(path, token string) benchGN04List {
	a.t.Helper()
	var out benchGN04List
	a.getJSON(path, token, &out)
	return out
}

func (a *benchGN04App) single(slug, token string) benchGN04Article {
	a.t.Helper()
	var out struct {
		Article benchGN04Article `json:"article"`
	}
	a.getJSON("/api/articles/"+slug, token, &out)
	return out.Article
}

func (a *benchGN04App) comments(slug string) benchGN04Comments {
	a.t.Helper()
	var out benchGN04Comments
	a.getJSON("/api/articles/"+slug+"/comments", "", &out)
	return out
}

func (a *benchGN04App) login(email, password string) benchGN04Resp {
	a.t.Helper()
	return a.do("POST", "/api/users/login", "", map[string]interface{}{"user": map[string]string{
		"email": email, "password": password}})
}

func (a *benchGN04App) update(token string, fields map[string]string) string {
	a.t.Helper()
	r := a.must(http.StatusOK, "PUT", "/api/user", token, map[string]interface{}{"user": fields})
	var out struct {
		User struct {
			Bio   string `json:"bio"`
			Image string `json:"image"`
		} `json:"user"`
	}
	if err := json.Unmarshal(r.Body, &out); err != nil {
		a.t.Fatalf("PUT /api/user: %v %s", err, r.Body)
	}
	if v, ok := fields["bio"]; ok && out.User.Bio != v {
		a.t.Fatalf("PUT /api/user: bio %q, want %q", out.User.Bio, v)
	}
	return benchGN04Token(a.t, r.Body, "PUT /api/user")
}

func TestBenchGN04PasswordSurvivesProfileUpdate(t *testing.T) {
	app := benchGN04Start(t)
	const original = "benchpassword1" // the password register() uses

	alice := app.register("alice")
	bobby := app.register("bobby")
	email := "alice@bench.example"

	if r := app.login(email, original); r.Status != http.StatusOK {
		t.Fatalf("login before any update: status %d, want 200; body: %s", r.Status, r.Body)
	}

	alice = app.update(alice, map[string]string{"bio": "I write about Go"})
	if r := app.login(email, original); r.Status != http.StatusOK {
		t.Fatalf("login with the unchanged password after a bio-only update: status %d, want 200; body: %s", r.Status, r.Body)
	}
	alice = app.update(alice, map[string]string{"image": "https://img.bench.example/alice.png"})
	if r := app.login(email, original); r.Status != http.StatusOK {
		t.Fatalf("login with the unchanged password after an image-only update: status %d, want 200; body: %s", r.Status, r.Body)
	}
	for _, wrong := range []string{"A String Very Very Very Random!!@##$!@#4", "", "benchpassword2"} {
		if r := app.login(email, wrong); r.Status == http.StatusOK {
			t.Fatalf("login with the wrong password %q after updates: status 200, want a rejection", wrong)
		}
	}
	var profile struct {
		Profile struct {
			Bio   string `json:"bio"`
			Image string `json:"image"`
		} `json:"profile"`
	}
	app.getJSON("/api/profiles/alice", "", &profile)
	if profile.Profile.Bio != "I write about Go" || profile.Profile.Image != "https://img.bench.example/alice.png" {
		t.Fatalf("profile after updates: %+v", profile.Profile)
	}

	// changing the password still works
	alice = app.update(alice, map[string]string{"password": "brandnewpass9"})
	if r := app.login(email, "brandnewpass9"); r.Status != http.StatusOK {
		t.Fatalf("login with the new password: status %d, want 200; body: %s", r.Status, r.Body)
	}
	if r := app.login(email, original); r.Status != http.StatusUnauthorized {
		t.Fatalf("login with the old password after a change: status %d, want 401", r.Status)
	}
	app.update(alice, map[string]string{"bio": "still writing"})
	if r := app.login(email, "brandnewpass9"); r.Status != http.StatusOK {
		t.Fatalf("login with the changed password after a later bio-only update: status %d, want 200; body: %s", r.Status, r.Body)
	}

	// other users are untouched, and the user endpoints keep their auth rules
	if r := app.login("bobby@bench.example", original); r.Status != http.StatusOK {
		t.Fatalf("bobby login: status %d, want 200", r.Status)
	}
	app.must(http.StatusOK, "GET", "/api/user", bobby, nil)
	app.must(http.StatusUnauthorized, "PUT", "/api/user", "", map[string]interface{}{"user": map[string]string{"bio": "x"}})
	if r := app.do("PUT", "/api/user", bobby, map[string]interface{}{"user": map[string]string{"password": "short"}}); r.Status != http.StatusUnprocessableEntity {
		t.Fatalf("PUT /api/user with a too short password: status %d, want 422", r.Status)
	}
}

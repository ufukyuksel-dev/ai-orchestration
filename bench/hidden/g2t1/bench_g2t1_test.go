// Hidden benchmark test for task g2t1. Black-box: it starts the real application (main) on a free port
// with a throw-away SQLite database (DB_PATH), creates its data through the public API and checks only the
// HTTP contract stated in the task.
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

type benchG2T1App struct {
	t    *testing.T
	base string
}

type benchG2T1Resp struct {
	Status      int
	ContentType string
	Body        []byte
}

func benchG2T1Start(t *testing.T) *benchG2T1App {
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
	return &benchG2T1App{t: t, base: "http://127.0.0.1:" + port}
}

func (a *benchG2T1App) do(method, path, token string, payload interface{}) benchG2T1Resp {
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
	return benchG2T1Resp{Status: resp.StatusCode, ContentType: resp.Header.Get("Content-Type"), Body: data}
}

func (a *benchG2T1App) must(status int, method, path, token string, payload interface{}) benchG2T1Resp {
	a.t.Helper()
	r := a.do(method, path, token, payload)
	if r.Status != status {
		a.t.Fatalf("%s %s: status %d, want %d; body: %s", method, path, r.Status, status, r.Body)
	}
	return r
}

func (a *benchG2T1App) register(username string) string {
	a.t.Helper()
	r := a.must(http.StatusCreated, "POST", "/api/users", "", map[string]interface{}{"user": map[string]string{
		"username": username, "email": username + "@bench.example", "password": "benchpassword1"}})
	var out struct {
		User struct {
			Token string `json:"token"`
		} `json:"user"`
	}
	if err := json.Unmarshal(r.Body, &out); err != nil || out.User.Token == "" {
		a.t.Fatalf("register %s: %v %s", username, err, r.Body)
	}
	return out.User.Token
}

func (a *benchG2T1App) article(token, title string, tags ...string) string {
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

func (a *benchG2T1App) deleteArticle(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug, token, nil)
}

func (a *benchG2T1App) comment(token, slug, body string) int {
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

func (a *benchG2T1App) deleteComment(token, slug string, id int) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug+"/comments/"+strconv.Itoa(id), token, nil)
}

func (a *benchG2T1App) follow(token, username string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/profiles/"+username+"/follow", token, nil)
}

func (a *benchG2T1App) unfollow(token, username string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/profiles/"+username+"/follow", token, nil)
}

func (a *benchG2T1App) favorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "POST", "/api/articles/"+slug+"/favorite", token, nil)
}

func (a *benchG2T1App) unfavorite(token, slug string) {
	a.t.Helper()
	a.must(http.StatusOK, "DELETE", "/api/articles/"+slug+"/favorite", token, nil)
}

// getJSON: status 200, an application/json content type, and the body decoded with numbers kept as json.Number.
func (a *benchG2T1App) getJSON(path, token string) map[string]interface{} {
	a.t.Helper()
	r := a.must(http.StatusOK, "GET", path, token, nil)
	if mt, _, err := mime.ParseMediaType(r.ContentType); err != nil || mt != "application/json" {
		a.t.Fatalf("GET %s: content type %q, want application/json", path, r.ContentType)
	}
	dec := json.NewDecoder(bytes.NewReader(r.Body))
	dec.UseNumber()
	var out map[string]interface{}
	if err := dec.Decode(&out); err != nil {
		a.t.Fatalf("GET %s: body is not a JSON object: %v: %s", path, err, r.Body)
	}
	return out
}

func benchG2T1Object(t *testing.T, v interface{}, what string) map[string]interface{} {
	t.Helper()
	m, ok := v.(map[string]interface{})
	if !ok {
		t.Fatalf("%s: want a JSON object, got %#v", what, v)
	}
	return m
}

func benchG2T1Int(t *testing.T, m map[string]interface{}, key, what string) int64 {
	t.Helper()
	n, ok := m[key].(json.Number)
	if !ok {
		t.Fatalf("%s: field %q must be a JSON number, got %#v", what, key, m[key])
	}
	i, err := n.Int64()
	if err != nil {
		t.Fatalf("%s: field %q must be an integer, got %v", what, key, n)
	}
	return i
}

func benchG2T1String(t *testing.T, m map[string]interface{}, key, what string) string {
	t.Helper()
	s, ok := m[key].(string)
	if !ok {
		t.Fatalf("%s: field %q must be a JSON string, got %#v", what, key, m[key])
	}
	return s
}

func benchG2T1Strings(t *testing.T, m map[string]interface{}, key, what string) []string {
	t.Helper()
	arr, ok := m[key].([]interface{})
	if !ok {
		t.Fatalf("%s: field %q must be a JSON array (also when empty), got %#v", what, key, m[key])
	}
	out := []string{}
	for _, v := range arr {
		s, ok := v.(string)
		if !ok {
			t.Fatalf("%s: field %q must contain strings, got %#v", what, key, v)
		}
		out = append(out, s)
	}
	return out
}

func benchG2T1EqualStrings(t *testing.T, got, want []string, what string) {
	t.Helper()
	if strings.Join(got, "\x00") != strings.Join(want, "\x00") || len(got) != len(want) {
		t.Fatalf("%s: got %q, want %q", what, got, want)
	}
}

// getCSV: status 200, a text/csv content type, and the body parsed with a strict RFC 4180 parser (CRLF or LF;
// quoting of any field allowed; bare quotes, junk after a closing quote, bare CR, unterminated quotes and a
// missing final line break are rejected).
func (a *benchG2T1App) getCSV(path, token string) [][]string {
	a.t.Helper()
	r := a.must(http.StatusOK, "GET", path, token, nil)
	if mt, _, err := mime.ParseMediaType(r.ContentType); err != nil || mt != "text/csv" {
		a.t.Fatalf("GET %s: content type %q, want text/csv", path, r.ContentType)
	}
	return benchG2T1ParseCSV(a.t, string(r.Body), path)
}

func benchG2T1ParseCSV(t *testing.T, s, what string) [][]string {
	t.Helper()
	if s == "" {
		t.Fatalf("%s: empty body", what)
	}
	var records [][]string
	var rec []string
	var field strings.Builder
	i, n := 0, len(s)
	for i < n {
		if s[i] == '"' {
			i++
			for {
				if i >= n {
					t.Fatalf("%s: unterminated quoted field in %q", what, s)
				}
				if s[i] == '"' {
					if i+1 < n && s[i+1] == '"' {
						field.WriteByte('"')
						i += 2
						continue
					}
					i++
					break
				}
				field.WriteByte(s[i])
				i++
			}
			if i < n && s[i] != ',' && s[i] != '\n' && s[i] != '\r' {
				t.Fatalf("%s: junk after a closing quote at offset %d in %q", what, i, s)
			}
		} else {
			for i < n && s[i] != ',' && s[i] != '\n' && s[i] != '\r' {
				if s[i] == '"' {
					t.Fatalf("%s: bare double quote in an unquoted field at offset %d in %q", what, i, s)
				}
				field.WriteByte(s[i])
				i++
			}
		}
		rec = append(rec, field.String())
		field.Reset()
		if i >= n {
			t.Fatalf("%s: the last line does not end with a line break: %q", what, s)
		}
		switch s[i] {
		case ',':
			i++
			if i >= n {
				t.Fatalf("%s: the last line does not end with a line break: %q", what, s)
			}
		case '\r':
			if i+1 >= n || s[i+1] != '\n' {
				t.Fatalf("%s: bare CR at offset %d in %q", what, i, s)
			}
			i += 2
			records = append(records, rec)
			rec = nil
		case '\n':
			i++
			records = append(records, rec)
			rec = nil
		}
	}
	return records
}

func benchG2T1EqualRecords(t *testing.T, got, want [][]string, what string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s: %d records, want %d\ngot:  %q\nwant: %q", what, len(got), len(want), got, want)
	}
	for i := range want {
		benchG2T1EqualStrings(t, got[i], want[i], what+": record "+strconv.Itoa(i))
	}
}

var _ = sort.Strings

func TestBenchG2T1ArticlesCSV(t *testing.T) {
	app := benchG2T1Start(t)

	alice := app.register("alice")
	bobby := app.register("bobby")
	carol := app.register("carol")
	tokens := map[string]string{"alice": alice, "bobby": bobby, "carol": carol}
	authors := []string{"carol", "alice", "bobby"}

	type row struct {
		slug, title, author, tags string
		favorites                 int
	}
	var rows []row
	var deleted string
	for i := 1; i <= 24; i++ {
		author := authors[i%3]
		title := "Plain Article " + strconv.Itoa(100+i)
		var tags []string
		joined := ""
		switch i {
		case 2:
			title = `Hello, "World"`
		case 5:
			title = "Two\nLines Title"
		case 3:
			tags, joined = []string{"zeta", "alpha"}, "alpha;zeta"
		case 7:
			tags, joined = []string{"go", "a,b"}, "a,b;go"
		case 8:
			tags, joined = []string{"solo"}, "solo"
		}
		slug := app.article(tokens[author], title, tags...)
		if i == 12 {
			deleted = slug
			app.deleteArticle(tokens[author], slug)
			continue
		}
		rows = append(rows, row{slug: slug, title: title, author: author, tags: joined})
	}
	app.favorite(bobby, rows[0].slug)
	app.favorite(carol, rows[0].slug)
	app.favorite(alice, rows[0].slug)
	app.unfavorite(alice, rows[0].slug)
	rows[0].favorites = 2
	app.favorite(alice, rows[6].slug)
	rows[6].favorites = 1
	app.must(http.StatusNotFound, "GET", "/api/articles/"+deleted, "", nil)

	want := [][]string{{"slug", "title", "author", "tagList", "favoritesCount", "createdAt"}}
	for _, r := range rows {
		article := benchG2T1Object(t, app.getJSON("/api/articles/"+r.slug, "")["article"], "GET /api/articles/"+r.slug)
		createdAt := benchG2T1String(t, article, "createdAt", "GET /api/articles/"+r.slug)
		want = append(want, []string{r.slug, r.title, r.author, r.tags, strconv.Itoa(r.favorites), createdAt})
	}
	for _, token := range []string{"", bobby} {
		got := app.getCSV("/api/articles.csv", token)
		benchG2T1EqualRecords(t, got, want, "GET /api/articles.csv (token: "+strconv.FormatBool(token != "")+")")
	}

	// existing behaviour is unchanged
	app.must(http.StatusOK, "GET", "/api/articles", "", nil)
	app.must(http.StatusOK, "GET", "/api/articles/"+rows[0].slug, "", nil)
	app.must(http.StatusUnauthorized, "POST", "/api/articles", "", map[string]interface{}{"article": map[string]interface{}{
		"title": "Anonymous Post", "description": "d", "body": "b"}})
}

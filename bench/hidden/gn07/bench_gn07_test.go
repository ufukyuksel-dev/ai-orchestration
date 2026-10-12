// Hidden benchmark test for task gn07 (refactor). It checks the new API (users.CurrentUser), that it returns what
// the authentication middleware stored, and the source-level acceptance criteria stated in the task: each context key
// literal in exactly one non-test .go file, and no Get/MustGet on a gin context in non-test code outside package
// users. Behavior is covered by the existing tests.
package users

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/gothinkster/golang-gin-realworld-example-app/common"
)

func TestBenchGN07CurrentUser(t *testing.T) {
	var current func(*gin.Context) UserModel = CurrentUser

	user := UserModel{Username: "benchgn07", Email: "benchgn07@bench.example", PasswordHash: "x"}
	if err := SaveOne(&user); err != nil {
		t.Fatalf("save user: %v", err)
	}

	r := gin.New()
	r.Use(AuthMiddleware(false))
	r.GET("/bench-gn07", func(c *gin.Context) {
		u := current(c)
		c.String(http.StatusOK, strconv.Itoa(int(u.ID))+":"+u.Username)
	})
	call := func(header string) string {
		req, _ := http.NewRequest("GET", "/bench-gn07", nil)
		if header != "" {
			req.Header.Set("Authorization", header)
		}
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("status %d", w.Code)
		}
		return w.Body.String()
	}
	if got, want := call("Token "+common.GenToken(user.ID)), strconv.Itoa(int(user.ID))+":benchgn07"; got != want {
		t.Fatalf("CurrentUser with a valid token: %q, want %q", got, want)
	}
	if got := call(""); got != "0:" {
		t.Fatalf("CurrentUser for an anonymous request: %q, want the zero UserModel", got)
	}
}

func TestBenchGN07SourceScope(t *testing.T) {
	root, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	var files []string
	err = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() && (info.Name() == ".git" || info.Name() == "vendor" || info.Name() == "node_modules") {
			return filepath.SkipDir
		}
		if !info.IsDir() && strings.HasSuffix(path, ".go") && !strings.HasSuffix(path, "_test.go") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	contextGet := regexp.MustCompile(`\.(MustGet|Get)\(`)
	holders := map[string][]string{`"my_user_model"`: nil, `"my_user_id"`: nil}
	var getters []string
	for _, path := range files {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		rel, _ := filepath.Rel(root, path)
		for literal := range holders {
			if strings.Contains(string(data), literal) {
				holders[literal] = append(holders[literal], rel)
			}
		}
		if filepath.Dir(rel) != "users" && contextGet.Match(data) {
			getters = append(getters, rel)
		}
	}
	for literal, in := range holders {
		sort.Strings(in)
		if len(in) != 1 {
			t.Errorf("the literal %s appears in %d non-test .go files, want exactly 1: %v", literal, len(in), in)
		}
	}
	if len(getters) > 0 {
		sort.Strings(getters)
		t.Errorf("non-test code outside package users calls Get/MustGet on a gin context: %v", getters)
	}
}

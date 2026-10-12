`app/errors/handlers.py` registers `@bp.app_errorhandler(404) not_found_error` for the whole app. It calls
`wants_json_response()`, which compares `request.accept_mimetypes['application/json'] >=
request.accept_mimetypes['text/html']`; without an `Accept` header both qualities are 0, so the comparison is true and
the handler returns `api_error_response(404)`: `{"error": "Not Found"}` with status 404. The `api` blueprint's own
`handle_exception` does not apply because the URL matches no `api` route.

ANSWER:
handler: app.errors.handlers.not_found_error
decider: wants_json_response
format: json
status: 404

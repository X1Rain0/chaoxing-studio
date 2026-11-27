package mobilebridge

import "testing"

func TestLoginRejectsMissingCredentials(t *testing.T) {
	bridge := NewBridge()
	if _, err := bridge.Login("", "password"); err == nil {
		t.Fatal("Login accepted an empty phone number")
	}
	if _, err := bridge.Login("13800000000", ""); err == nil {
		t.Fatal("Login accepted an empty password")
	}
}

func TestOperationsRequireLogin(t *testing.T) {
	bridge := NewBridge()
	if _, err := bridge.Courses(); err == nil {
		t.Fatal("Courses succeeded without a session")
	}
	if _, err := bridge.Sign(`{}`, `{}`); err == nil {
		t.Fatal("Sign succeeded without a session")
	}
	if _, err := bridge.UploadPanPhoto("image/png", []byte("fixture")); err == nil {
		t.Fatal("UploadPanPhoto succeeded without a session")
	}
}

func TestCheckActivitiesValidatesSelectedCourses(t *testing.T) {
	bridge := NewBridge()
	if _, err := bridge.CheckActivities(`{`); err == nil {
		t.Fatal("CheckActivities accepted malformed JSON")
	}
	if _, err := bridge.CheckActivities(`[]`); err == nil {
		t.Fatal("CheckActivities accepted an empty selection")
	}
}

func TestSignValidatesJSONBeforeSession(t *testing.T) {
	bridge := NewBridge()
	if _, err := bridge.Sign(`{`, `{}`); err == nil {
		t.Fatal("Sign accepted malformed activity JSON")
	}
	if _, err := bridge.Sign(`{}`, `{`); err == nil {
		t.Fatal("Sign accepted malformed input JSON")
	}
}

func TestLogoutIsIdempotent(t *testing.T) {
	bridge := NewBridge()
	bridge.Logout()
	bridge.Logout()
	if _, err := bridge.Courses(); err == nil {
		t.Fatal("Courses succeeded after logout")
	}
}

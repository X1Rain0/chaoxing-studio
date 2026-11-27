// Package mobilebridge exposes a narrow, gomobile-friendly facade over the
// Chaoxing client. JSON is used at the Kotlin boundary so the AAR surface stays
// limited to strings and errors rather than complex Go types.
package mobilebridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"chaoxing-studio/internal/chaoxing"
)

var errNotLoggedIn = errors.New("尚未登录")

// Bridge is a serialized session facade intended to be owned by one Android
// process. Call it from an IO dispatcher; network operations are synchronous.
type Bridge struct {
	mu     sync.Mutex
	client *chaoxing.Client
}

type loginResult struct {
	Name    string            `json:"name"`
	Courses []chaoxing.Course `json:"courses"`
}

type activityScanResult struct {
	CheckedAt    time.Time           `json:"checkedAt"`
	CourseCount  int                 `json:"courseCount"`
	Activities   []chaoxing.Activity `json:"activities"`
	CourseErrors []courseScanError   `json:"courseErrors"`
}

type courseScanError struct {
	CourseID string `json:"courseId"`
	ClassID  string `json:"classId"`
	Course   string `json:"course"`
	Message  string `json:"message"`
}

// NewBridge creates an empty session facade.
func NewBridge() *Bridge { return &Bridge{} }

// Login authenticates and returns the display name and available courses as
// JSON. The credential remains only in the in-memory Chaoxing client.
func (b *Bridge) Login(phone, password string) (string, error) {
	if b == nil {
		return "", errors.New("bridge 未初始化")
	}
	if strings.TrimSpace(phone) == "" || password == "" {
		return "", errors.New("请输入手机号和密码")
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	next := chaoxing.NewClient(chaoxing.Credential{Phone: strings.TrimSpace(phone), Password: password}, nil)
	if err := next.Login(); err != nil {
		next.Close()
		return "", err
	}
	courses, err := next.Courses()
	if err != nil {
		next.Close()
		return "", err
	}

	encoded, err := json.Marshal(loginResult{Name: next.Name(), Courses: courses})
	if err != nil {
		next.Close()
		return "", fmt.Errorf("序列化登录结果失败: %w", err)
	}
	if b.client != nil {
		b.client.Close()
	}
	b.client = next
	return string(encoded), nil
}

// Courses returns the account's current course list as JSON.
func (b *Bridge) Courses() (string, error) {
	if b == nil {
		return "", errors.New("bridge 未初始化")
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return "", errNotLoggedIn
	}
	courses, err := b.client.Courses()
	if err != nil {
		return "", err
	}
	return marshal(courses)
}

// CheckActivities scans the selected courses supplied as a JSON array of
// chaoxing Course objects. A per-course failure is returned in the JSON result
// so one inaccessible course does not hide activities from the remaining ones.
func (b *Bridge) CheckActivities(coursesJSON string) (string, error) {
	var courses []chaoxing.Course
	if err := json.Unmarshal([]byte(coursesJSON), &courses); err != nil {
		return "", fmt.Errorf("课程列表格式错误: %w", err)
	}
	if len(courses) == 0 {
		return "", errors.New("请至少选择一门课程")
	}
	if b == nil {
		return "", errors.New("bridge 未初始化")
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return "", errNotLoggedIn
	}

	result := activityScanResult{
		CheckedAt:    time.Now(),
		CourseCount:  len(courses),
		Activities:   make([]chaoxing.Activity, 0),
		CourseErrors: make([]courseScanError, 0),
	}
	for _, course := range courses {
		activities, err := b.client.Activities(course.CourseID, course.ClassID)
		if err != nil {
			result.CourseErrors = append(result.CourseErrors, courseScanError{
				CourseID: course.CourseID,
				ClassID:  course.ClassID,
				Course:   course.Title(),
				Message:  err.Error(),
			})
			continue
		}
		for i := range activities {
			activities[i].Course = course
			if activities[i].OtherID == 0 {
				activities[i].IsPhoto = b.client.IsPhotoActivity(activities[i].ActiveID)
				activities[i].Kind = chaoxing.KindFromOtherID(0, activities[i].IsPhoto)
				activities[i].KindName = activities[i].Kind.Label()
			}
			result.Activities = append(result.Activities, activities[i])
		}
	}
	return marshal(result)
}

// UploadPanPhoto uploads one user-selected, bounded JPEG/PNG and returns its
// object ID. The Android layer keeps that ID in memory for one sign dialog.
func (b *Bridge) UploadPanPhoto(mimeType string, data []byte) (string, error) {
	if b == nil {
		return "", errors.New("bridge 未初始化")
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return "", errNotLoggedIn
	}
	return b.client.UploadPanPhoto(mimeType, data)
}

// Sign submits one activity. activityJSON is a Chaoxing Activity and
// inputJSON is a Chaoxing SignInput; both are intentionally opaque to gobind.
func (b *Bridge) Sign(activityJSON, inputJSON string) (string, error) {
	var activity chaoxing.Activity
	if err := json.Unmarshal([]byte(activityJSON), &activity); err != nil {
		return "", fmt.Errorf("签到活动格式错误: %w", err)
	}
	var input chaoxing.SignInput
	if err := json.Unmarshal([]byte(inputJSON), &input); err != nil {
		return "", fmt.Errorf("签到参数格式错误: %w", err)
	}
	if b == nil {
		return "", errors.New("bridge 未初始化")
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return "", errNotLoggedIn
	}
	result, err := b.client.Sign(activity, input)
	if err != nil {
		return "", err
	}
	return marshal(result)
}

// Logout drops the in-memory client. The Android layer separately removes the
// encrypted credential from Android Keystore-backed storage.
func (b *Bridge) Logout() {
	if b == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client != nil {
		b.client.Close()
		b.client = nil
	}
}

func marshal(value any) (string, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "", fmt.Errorf("序列化结果失败: %w", err)
	}
	return string(encoded), nil
}

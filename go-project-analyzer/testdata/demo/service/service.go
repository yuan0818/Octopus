package service

import (
	"context"
	"fmt"

	"demo.example.com/demo/model"
	"demo.example.com/demo/store"
)

// Service depends on the Store interface (dispatch fan-out target).
type Service struct {
	Store store.Store
}

// GetUser goes through the interface: must fan out to store.DbStore.Get.
func (s *Service) GetUser(ctx context.Context, id string) (*model.User, error) {
	return s.Store.Get(ctx, id)
}

// Logger is implemented by nothing in the workspace: dispatch to it must
// fall back to an INTERFACE edge at the Logger.Log node.
type Logger interface {
	Log(msg string)
}

// Warn dispatches to the unimplemented Logger interface.
func (s *Service) Warn(l Logger, msg string) {
	l.Log(msg)
}

// Format is a free function using the model package.
func Format(u *model.User) string {
	return fmt.Sprintf("%s", u.DisplayName())
}

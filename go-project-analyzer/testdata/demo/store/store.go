package store

import (
	"context"

	"demo.example.com/demo/model"
)

// Store is the persistence interface implemented by DbStore.
type Store interface {
	Get(ctx context.Context, id string) (*model.User, error)
}

// Base carries shared behavior promoted into DbStore via embedding.
type Base struct {
	DSN string
}

// Hello is promoted to DbStore through embedding.
func (b *Base) Hello() string {
	return b.DSN
}

// DbStore implements Store.
type DbStore struct {
	Base
	table string
}

// Get loads a user by id.
func (s *DbStore) Get(ctx context.Context, id string) (*model.User, error) {
	if err := validateID(id); err != nil {
		return nil, err
	}
	return &model.User{ID: id}, nil
}

// validateID is a package-level free function.
func validateID(id string) error {
	if id == "" {
		return errEmptyID
	}
	return nil
}

// errEmptyID is a sentinel error value (not a function).
var errEmptyID = error(nil)

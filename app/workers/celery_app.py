"""Celery application.

Reliability settings:
* ``task_acks_late`` + ``task_reject_on_worker_lost``: a message is acknowledged only after the task
  finishes, so a crashed worker's task is redelivered. Every task is idempotent (see jobs/services).
* ``worker_prefetch_multiplier=1``: no worker hoards messages it cannot start.
* Soft/hard time limits on every task; ``visibility_timeout`` above the hard limit.
* Retries use exponential backoff with jitter (``retry_backoff``/``retry_jitter``), bounded by
  ``max_retries``. When a task exhausts them it is written to ``system_dead_letters`` and its
  ``system_tasks`` row is marked failed. Webhook and push deliveries keep their own durable
  per-row retry schedule in PostgreSQL instead of broker retries.
* No result backend: task state lives in ``system_tasks``, which is where tenant authorisation is
  enforced.

Start a worker:  ``celery -A app.workers.celery_app worker -Q default,deliveries,imports,maintenance``
Start beat:      ``celery -A app.workers.celery_app beat``
"""

from __future__ import annotations

from celery import Celery

from app.core.config import get_settings

_s = get_settings()

celery = Celery("tenantcore", broker=_s.celery_broker_url.get_secret_value(), include=["app.workers.tasks"])
celery.conf.update(
    task_acks_late=True,
    task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1,
    task_ignore_result=True,
    task_store_errors_even_if_ignored=False,
    task_soft_time_limit=_s.celery_task_soft_time_limit_s,
    task_time_limit=_s.celery_task_time_limit_s,
    broker_transport_options={
        "visibility_timeout": max(3600, _s.celery_task_time_limit_s * 2),
        "socket_connect_timeout": 2,
        "socket_timeout": 5,
    },
    broker_connection_timeout=2,
    broker_connection_retry_on_startup=True,
    task_default_queue="default",
    task_routes={
        "tenantcore.dispatch_webhooks": {"queue": "deliveries"},
        "tenantcore.dispatch_notifications": {"queue": "deliveries"},
        "tenantcore.run_import": {"queue": "imports"},
        "tenantcore.offboard_tenant": {"queue": "maintenance"},
        "tenantcore.cleanup": {"queue": "maintenance"},
    },
    beat_schedule={
        "relay-outbox": {"task": "tenantcore.relay_outbox", "schedule": 5.0},
        "dispatch-webhooks": {"task": "tenantcore.dispatch_webhooks", "schedule": 10.0},
        "dispatch-notifications": {"task": "tenantcore.dispatch_notifications", "schedule": 10.0},
        "requeue-stale-tasks": {"task": "tenantcore.requeue_stale_tasks", "schedule": 60.0},
        "cleanup": {"task": "tenantcore.cleanup", "schedule": 3600.0},
    },
    worker_hijack_root_logger=False,
)

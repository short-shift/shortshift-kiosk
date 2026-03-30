-- Fjernkommandoer fra backoffice til skjermer via Supabase Realtime
CREATE TABLE IF NOT EXISTS device_commands (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    screen_id UUID NOT NULL REFERENCES screens(id) ON DELETE CASCADE,
    command_type TEXT NOT NULL CHECK (command_type IN ('set_url', 'reboot', 'refresh', 'screenshot')),
    payload TEXT,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'failed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    executed_at TIMESTAMPTZ
);

-- Index for rask oppslag
CREATE INDEX IF NOT EXISTS idx_device_commands_screen_pending
    ON device_commands(screen_id, status) WHERE status = 'pending';

-- RLS
ALTER TABLE device_commands ENABLE ROW LEVEL SECURITY;

CREATE POLICY device_commands_select ON device_commands FOR SELECT USING (true);
CREATE POLICY device_commands_update ON device_commands FOR UPDATE USING (true);
CREATE POLICY device_commands_insert ON device_commands FOR INSERT WITH CHECK (true);

-- Aktiver realtime
ALTER PUBLICATION supabase_realtime ADD TABLE device_commands;

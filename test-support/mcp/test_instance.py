"""Launcher safety checks use mocked process/container boundaries, never a database."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mcp_instance', Path(__file__).with_name('instance.py'))
instance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(instance)


class InstanceSafetyTest(unittest.TestCase):
    def test_relative_state_refuses_before_any_process_or_container(self):
        with patch('sys.argv', ['instance.py', 'start', 'relative']), patch.object(instance, 'run') as run:
            with self.assertRaises(SystemExit):
                instance.main()
            run.assert_not_called()

    def test_checkout_state_refuses_before_any_process_or_container(self):
        with patch('sys.argv', ['instance.py', 'start', str(instance.ROOT / 'build/unsafe-state')]), patch.object(instance, 'run') as run:
            with self.assertRaises(SystemExit):
                instance.main()
            run.assert_not_called()

    def test_existing_unclaimed_directory_is_never_adopted_or_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary)
            retained = state / 'retained.txt'
            retained.write_text('unrelated')
            with patch('sys.argv', ['instance.py', 'start', str(state)]), patch.object(instance, 'run') as run:
                with self.assertRaises(FileExistsError):
                    instance.main()
                run.assert_not_called()
            self.assertEqual('unrelated', retained.read_text())

    def test_reused_foreign_pid_is_not_signalled(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary)
            (state / 'server.pid').write_text('123')
            with patch.object(instance, 'alive', return_value=True), patch.object(instance, 'run', return_value='unrelated-command'), patch.object(instance.os, 'kill') as kill:
                with self.assertRaisesRegex(RuntimeError, 'unrelated process'):
                    instance.stop_process(state, 'server')
                kill.assert_not_called()
            self.assertTrue((state / 'server.pid').exists())

    def test_dead_pid_hint_is_removed_without_signalling(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary)
            (state / 'server.pid').write_text('123')
            with patch.object(instance, 'alive', return_value=False), patch.object(instance.os, 'kill') as kill:
                instance.stop_process(state, 'server')
                kill.assert_not_called()
            self.assertFalse((state / 'server.pid').exists())


if __name__ == '__main__':
    unittest.main()

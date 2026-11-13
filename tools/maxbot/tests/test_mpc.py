"""Optional numerical tests use requirements-mpc.txt; core tooling stays stdlib-only."""
import importlib.util
import pathlib
import unittest

@unittest.skipUnless(importlib.util.find_spec('osqp'),'Install requirements-mpc.txt to verify the numerical MPC formulation')
class MpcFormulationTest(unittest.TestCase):
    def test_structure_and_zero_equilibrium(self):
        import numpy as np
        import osqp
        path=pathlib.Path(__file__).resolve().parents[1]/'generate_mpc.py'
        spec=importlib.util.spec_from_file_location('generate_mpc',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        p,a=module.matrices();self.assertEqual(a.shape,(200,120));self.assertEqual(a.nnz,354)
        lo=np.full(200,-1.);hi=np.full(200,1.);lo[:80]=0;hi[:80]=0
        solver=osqp.OSQP();solver.setup(P=p,q=np.zeros(120),A=a,l=lo,u=hi,verbose=False)
        result=solver.solve();self.assertEqual(result.info.status,'solved');self.assertLess(np.max(np.abs(result.x)),1e-8)

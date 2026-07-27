import {createRouter,createWebHistory} from 'vue-router';
import {useAuthStore} from '@/stores/auth';

const routes=[
  {path:'/login',component:()=>import('@/views/LoginView.vue')},
  {path:'/',component:()=>import('@/layout/AppLayout.vue'),children:[
    {path:'',redirect:'/dashboard'},
    {path:'dashboard',component:()=>import('@/views/DashboardView.vue')},
    {path:'employees',component:()=>import('@/views/EmployeesView.vue'),meta:{permission:'employee:read'}},
    {path:'employee-directory',component:()=>import('@/views/EmployeeDirectoryView.vue'),meta:{permission:'employee:export'}},
    {path:'courses',component:()=>import('@/views/CoursesView.vue')},
    {path:'training-plans',component:()=>import('@/views/TrainingPlansView.vue'),meta:{permission:'task:manage'}},
    {path:'tasks',component:()=>import('@/views/TasksView.vue')},
    {path:'evaluation',component:()=>import('@/views/EvaluationView.vue'),meta:{permission:'evaluation:view'}},
    {path:'exams',redirect:'/exams/my'},
    {path:'exams/my',component:()=>import('@/views/exams/MyExamsView.vue')},
    {path:'exams/questions',component:()=>import('@/views/exams/ExamQuestionBankView.vue'),meta:{permission:'exam:manage'}},
    {path:'exams/papers',component:()=>import('@/views/exams/ExamPapersView.vue'),meta:{permission:'exam:manage'}},
    {path:'exams/plans',component:()=>import('@/views/exams/ExamPlansView.vue'),meta:{permission:'exam:manage'}},
    {path:'exams/results',component:()=>import('@/views/exams/ExamResultsView.vue')},
    {path:'users',component:()=>import('@/views/UsersView.vue'),meta:{permission:'user:employee:manage'}},
    {path:'profile',component:()=>import('@/views/ProfileView.vue')}
  ]}
];

const router=createRouter({history:createWebHistory(),routes});
router.beforeEach(to=>{
  const a=useAuthStore();
  if(to.path!='/login'&&!a.user)return '/login';
  if(to.path==='/login'&&a.user)return '/dashboard';
  if(a.user?.mustChangePassword&&to.path!='/profile')return '/profile';
  if(a.user?.role==='EMPLOYEE'&&to.path==='/employees')return '/profile';
  if(a.user?.role!=='EMPLOYEE'&&to.path==='/exams/my')return a.can('exam:manage')?'/exams/plans':'/exams/results';
  const permission=to.meta.permission as string|undefined;
  if(permission&&!a.can(permission))return '/dashboard';
});
export default router;
